package dev.maverick.ftbridge;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import dev.maverick.ftbridge.control.ControlProtocol;

/**
 * Foreground service owning the native bridge thread. It keeps running while
 * other (VR) apps are in the foreground; whether the OpenXR runtime keeps
 * serving tracking data in that state is up to the platform.
 *
 * With gate apps configured, the bridge itself only runs while one of them
 * (or the settings app, which shows the live status) is in the foreground.
 */
public final class TrackingService extends Service {
    public static final String ACTION_START = "dev.maverick.ftbridge.START";
    public static final String ACTION_STOP = "dev.maverick.ftbridge.STOP";
    // ACTION_START takes the Settings keys as optional extras, which are persisted. This is
    // how the service is provisioned over adb without touching the UI.
    // Diagnostics, not persisted: run the bridge regardless of the gate and log
    // XR_HTC_eye_tracker samples (tools/probe-eye-tracker.ps1). Cleared by the next START.
    public static final String EXTRA_EYE_PROBE = "eyeprobe";

    private static final String TAG = "ftbridge";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final long GATE_CHECK_INTERVAL_MS = 2000;
    // A restart right after a stop is refused while the old bridge thread winds down
    private static final long START_RETRY_INTERVAL_MS = 500;
    private static final int START_RETRY_LIMIT = 20;
    // Overlap between successive usage event queries, and the initial look-back
    private static final long GATE_QUERY_OVERLAP_MS = 10_000;
    private static final long GATE_INITIAL_LOOKBACK_MS = 24L * 60 * 60 * 1000;

    /** Human readable gate state for the control panel. */
    public static volatile String gateStatus = "";
    private static volatile boolean running;

    // The OpenXR runtime keeps a reference to this object for the process lifetime
    private static HeadlessActivity xrActivity;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable gateCheck = this::checkGate;
    private int startAttempts;
    private final Runnable startWhenReady = new Runnable() {
        @Override
        public void run() {
            if (startBridge() || ++startAttempts >= START_RETRY_LIMIT) {
                return;
            }
            handler.postDelayed(this, START_RETRY_INTERVAL_MS);
        }
    };
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Settings.Values settings;
    private boolean eyeProbe;
    private boolean bridgeRunning;
    private String foregroundPackage;
    private long lastUsageQueryTime;

    public static void start(Context context) {
        Intent intent = new Intent(context, TrackingService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, TrackingService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireLocks();
        running = true;

        Settings store = new Settings(this);
        settings = store.load();
        if (intent != null && intent.getExtras() != null) {
            settings.applyExtras(intent.getExtras());
            store.store(settings);
        }
        eyeProbe = intent != null && intent.getBooleanExtra(EXTRA_EYE_PROBE, false);
        if (xrActivity == null) {
            xrActivity = new HeadlessActivity(this);
        }

        // Restart the bridge so changed settings take effect
        handler.removeCallbacks(gateCheck);
        handler.removeCallbacks(startWhenReady);
        stopBridge();
        startAttempts = 0;

        if (!settings.eyeTracking && !settings.faceTracking) {
            Log.w(TAG, "eye and face tracking are both disabled, nothing to forward");
            gateStatus = "eye and face tracking are both disabled";
            return START_STICKY;
        }

        String gatePackages = ControlProtocol.joinPackageList(settings.gatePackages);
        if (eyeProbe) {
            Log.i(TAG, "eye probe: running regardless of the gate");
            gateStatus = "eye probe, running always";
            handler.post(startWhenReady);
        } else if (settings.runsAlways()) {
            gateStatus = settings.alwaysForward ? "always forwarding" : "no gate apps, running always";
            handler.post(startWhenReady);
        } else if (!hasUsageAccess(this)) {
            Log.w(TAG, "usage access not granted, cannot gate on " + gatePackages + "; running always");
            gateStatus = "usage access not granted, running always";
            handler.post(startWhenReady);
        } else {
            foregroundPackage = null;
            lastUsageQueryTime = 0;
            handler.post(gateCheck);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(gateCheck);
        handler.removeCallbacks(startWhenReady);
        NativeCore.stop();
        running = false;
        gateStatus = "";
        releaseLocks();
        super.onDestroy();
    }

    /** Returns false if the core refused (previous thread still stopping, or bad settings). */
    private boolean startBridge() {
        Log.i(TAG, "starting bridge -> " + settings.host + ":" + settings.port
                + " @ " + settings.rateHz + " Hz, frame rate " + settings.frameRateHz + " Hz"
                + ", eye " + settings.eyeTracking + (settings.preciseEye ? " (precise)" : "")
                + ", face " + settings.faceTracking + (eyeProbe ? ", eye probe" : ""));
        bridgeRunning = NativeCore.start(xrActivity, settings.host, settings.port,
                settings.rateHz, settings.frameRateHz, settings.eyeTracking, settings.preciseEye,
                settings.faceTracking, eyeProbe);
        return bridgeRunning;
    }

    private void stopBridge() {
        if (bridgeRunning) {
            Log.i(TAG, "stopping bridge");
            NativeCore.stop();
            bridgeRunning = false;
        }
    }

    private void checkGate() {
        updateForegroundPackage();

        boolean allowed = settings.gatePackages.contains(foregroundPackage)
                || ControlProtocol.SETTINGS_PACKAGE.equals(foregroundPackage);
        if (allowed && !bridgeRunning) {
            Log.i(TAG, "gate: " + foregroundPackage + " in foreground");
            startBridge();
        } else if (!allowed && bridgeRunning) {
            Log.i(TAG, "gate: gate app left the foreground (now " + foregroundPackage + ")");
            stopBridge();
        }

        gateStatus = bridgeRunning
                ? "active (" + foregroundPackage + " in foreground)"
                : "waiting for " + ControlProtocol.joinPackageList(settings.gatePackages)
                        + " (foreground: " + foregroundPackage + ")";
        handler.postDelayed(gateCheck, GATE_CHECK_INTERVAL_MS);
    }

    /** Tracks the foreground app from usage events; only new events are read on each call. */
    private void updateForegroundPackage() {
        UsageStatsManager usageStats = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long from = lastUsageQueryTime == 0
                ? now - GATE_INITIAL_LOOKBACK_MS
                : lastUsageQueryTime - GATE_QUERY_OVERLAP_MS;
        lastUsageQueryTime = now;

        UsageEvents events = usageStats.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            switch (event.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED:
                    foregroundPackage = event.getPackageName();
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                    if (event.getPackageName().equals(foregroundPackage)) {
                        foregroundPackage = null;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Face tracking bridge", NotificationManager.IMPORTANCE_LOW));

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FT Bridge")
                .setContentText("Streaming face tracking")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true);

        // Opens the settings app if it is installed (it is a separate package, see ControlService)
        Intent settingsIntent = new Intent().setComponent(new ComponentName(
                ControlProtocol.SETTINGS_PACKAGE, ControlProtocol.SETTINGS_ACTIVITY_CLASS));
        if (getPackageManager().resolveActivity(settingsIntent, 0) != null) {
            builder.setContentIntent(PendingIntent.getActivity(
                    this, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE));
        }

        return builder.build();
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager powerManager = getSystemService(PowerManager.class);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":bridge");
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wifiManager = getSystemService(WifiManager.class);
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }
}

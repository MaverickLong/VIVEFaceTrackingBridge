package dev.maverick.ftbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Foreground service owning the native bridge thread. It keeps running while
 * other (VR) apps are in the foreground; whether the OpenXR runtime keeps
 * serving tracking data in that state is up to the platform.
 */
public final class TrackingService extends Service {
    public static final String ACTION_START = "dev.maverick.ftbridge.START";
    public static final String ACTION_STOP = "dev.maverick.ftbridge.STOP";

    private static final String TAG = "ftbridge";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 1;

    // The OpenXR runtime keeps a reference to this object for the process lifetime
    private static HeadlessActivity xrActivity;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, TrackingService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, TrackingService.class).setAction(ACTION_STOP);
        context.startService(intent);
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

        Settings settings = new Settings(this);
        Log.i(TAG, "starting bridge -> " + settings.host() + ":" + settings.port()
                + " @ " + settings.rateHz() + " Hz");
        if (xrActivity == null) {
            xrActivity = new HeadlessActivity(this);
        }
        NativeCore.start(xrActivity, settings.host(), settings.port(), settings.rateHz());

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        NativeCore.stop();
        releaseLocks();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Face tracking bridge", NotificationManager.IMPORTANCE_LOW));

        PendingIntent openActivity = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FT Bridge")
                .setContentText("Streaming face tracking")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(openActivity)
                .setOngoing(true)
                .build();
    }

    private void acquireLocks() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":bridge");
        wakeLock.acquire();

        WifiManager wifiManager = getSystemService(WifiManager.class);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG);
        wifiLock.acquire();
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

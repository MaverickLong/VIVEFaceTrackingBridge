package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Persistent configuration. {@link Values} is the in-memory form; its keys double as the
 * extras of the START intent (adb provisioning) and of the control interface (settings app).
 */
public final class Settings {
    public static final int DEFAULT_PORT = 0xA1F7;
    // The VIVE eye and face trackers sample at 60 Hz
    public static final float DEFAULT_RATE_HZ = 60f;
    // Independent of the poll rate: frames only keep the session running. Measured on the
    // Focus Vision while Virtual Desktop streams: ~7% of one core at 10 Hz frames, ~10% at
    // 60 Hz, with the trackers polled at 60 Hz either way.
    public static final float DEFAULT_FRAME_RATE_HZ = 10f;
    // Only run the bridge while this app is in the foreground (empty: always run). The VIVE
    // runtime powers the trackers down anyway when no VR app is active.
    public static final String DEFAULT_GATE_PACKAGE = "VirtualDesktop.Android";

    // Extra / bundle keys, e.g.:
    //   adb shell am start-foreground-service -n dev.maverick.ftbridge/.TrackingService \
    //       -a dev.maverick.ftbridge.START --es host 192.168.1.10 --es gate VirtualDesktop.Android
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_RATE_HZ = "rate";
    public static final String KEY_FRAME_RATE_HZ = "framerate";
    public static final String KEY_AUTOSTART = "autostart";
    // Null or empty disables gating (--esn gate)
    public static final String KEY_GATE_PACKAGE = "gate";

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_RATE = "rate_hz";
    private static final String PREF_AUTOSTART = "autostart";
    private static final String PREF_FRAME_RATE = "frame_rate_hz";
    private static final String PREF_GATE_PACKAGE = "gate_package";

    /** One consistent snapshot of all settings. */
    public static final class Values {
        public String host = "";
        public int port = DEFAULT_PORT;
        public float rateHz = DEFAULT_RATE_HZ;
        public float frameRateHz = DEFAULT_FRAME_RATE_HZ;
        public boolean autostart = false;
        public String gatePackage = DEFAULT_GATE_PACKAGE;

        /** Overrides the fields whose keys are present; other fields are left alone. */
        public void applyExtras(Bundle extras) {
            if (extras == null) {
                return;
            }
            if (extras.containsKey(KEY_HOST)) {
                host = trimmed(extras.getString(KEY_HOST));
            }
            port = extras.getInt(KEY_PORT, port);
            rateHz = extras.getFloat(KEY_RATE_HZ, rateHz);
            frameRateHz = extras.getFloat(KEY_FRAME_RATE_HZ, frameRateHz);
            autostart = extras.getBoolean(KEY_AUTOSTART, autostart);
            if (extras.containsKey(KEY_GATE_PACKAGE)) {
                gatePackage = trimmed(extras.getString(KEY_GATE_PACKAGE));
            }
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putString(KEY_HOST, host);
            bundle.putInt(KEY_PORT, port);
            bundle.putFloat(KEY_RATE_HZ, rateHz);
            bundle.putFloat(KEY_FRAME_RATE_HZ, frameRateHz);
            bundle.putBoolean(KEY_AUTOSTART, autostart);
            bundle.putString(KEY_GATE_PACKAGE, gatePackage);
            return bundle;
        }

        private static String trimmed(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final SharedPreferences preferences;

    public Settings(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public Values load() {
        Values values = new Values();
        values.host = preferences.getString(PREF_HOST, values.host);
        values.port = preferences.getInt(PREF_PORT, values.port);
        values.rateHz = preferences.getFloat(PREF_RATE, values.rateHz);
        values.frameRateHz = preferences.getFloat(PREF_FRAME_RATE, values.frameRateHz);
        values.autostart = preferences.getBoolean(PREF_AUTOSTART, values.autostart);
        values.gatePackage = preferences.getString(PREF_GATE_PACKAGE, values.gatePackage);
        return values;
    }

    public void store(Values values) {
        preferences.edit()
                .putString(PREF_HOST, values.host)
                .putInt(PREF_PORT, values.port)
                .putFloat(PREF_RATE, values.rateHz)
                .putFloat(PREF_FRAME_RATE, values.frameRateHz)
                .putBoolean(PREF_AUTOSTART, values.autostart)
                .putString(PREF_GATE_PACKAGE, values.gatePackage)
                .apply();
    }
}

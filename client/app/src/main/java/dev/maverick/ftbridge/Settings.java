package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;

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

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_RATE = "rate_hz";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_FRAME_RATE = "frame_rate_hz";
    private static final String KEY_GATE_PACKAGE = "gate_package";

    private final SharedPreferences preferences;

    public Settings(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public String host() {
        return preferences.getString(KEY_HOST, "");
    }

    public int port() {
        return preferences.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public float rateHz() {
        return preferences.getFloat(KEY_RATE, DEFAULT_RATE_HZ);
    }

    public boolean autostart() {
        return preferences.getBoolean(KEY_AUTOSTART, false);
    }

    public float frameRateHz() {
        return preferences.getFloat(KEY_FRAME_RATE, DEFAULT_FRAME_RATE_HZ);
    }

    public String gatePackage() {
        return preferences.getString(KEY_GATE_PACKAGE, DEFAULT_GATE_PACKAGE);
    }

    public void save(String host, int port, float rateHz, boolean autostart, float frameRateHz,
                     String gatePackage) {
        preferences.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putFloat(KEY_RATE, rateHz)
                .putBoolean(KEY_AUTOSTART, autostart)
                .putFloat(KEY_FRAME_RATE, frameRateHz)
                .putString(KEY_GATE_PACKAGE, gatePackage == null ? "" : gatePackage.trim())
                .apply();
    }
}

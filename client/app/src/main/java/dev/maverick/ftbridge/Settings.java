package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class Settings {
    public static final int DEFAULT_PORT = 0xA1F7;
    // The VIVE eye and face trackers sample at 60 Hz
    public static final float DEFAULT_RATE_HZ = 60f;
    // Matched to the trackers. Measured on the Focus Vision while Virtual Desktop streams:
    // ~10% of one core at 60 Hz frames, ~7% at 10 Hz, with identical tracker output.
    public static final float DEFAULT_FRAME_RATE_HZ = 60f;

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_RATE = "rate_hz";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_FRAME_RATE = "frame_rate_hz";

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

    public void save(String host, int port, float rateHz, boolean autostart, float frameRateHz) {
        preferences.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putFloat(KEY_RATE, rateHz)
                .putBoolean(KEY_AUTOSTART, autostart)
                .putFloat(KEY_FRAME_RATE, frameRateHz)
                .apply();
    }
}

package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;

public final class Settings {
    public static final int DEFAULT_PORT = 0xA1F7;
    public static final float DEFAULT_RATE_HZ = 90f;

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_RATE = "rate_hz";
    private static final String KEY_AUTOSTART = "autostart";

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

    public void save(String host, int port, float rateHz, boolean autostart) {
        preferences.edit()
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putFloat(KEY_RATE, rateHz)
                .putBoolean(KEY_AUTOSTART, autostart)
                .apply();
    }
}

package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    // Streaming apps the bridge can be gated on (run only while one of them is in the
    // foreground). The VIVE runtime powers the trackers down anyway when no VR app is active.
    public static final String VIRTUAL_DESKTOP_PACKAGE = "VirtualDesktop.Android";
    public static final String STEAM_LINK_PACKAGE = "com.valvesoftware.steamlinkvr";
    public static final List<String> DEFAULT_GATE_PACKAGES =
            Collections.singletonList(VIRTUAL_DESKTOP_PACKAGE);

    // Extra / bundle keys, e.g.:
    //   adb shell am start-foreground-service -n dev.maverick.ftbridge/.TrackingService \
    //       -a dev.maverick.ftbridge.START --es host 192.168.1.10 --es gate VirtualDesktop.Android
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_RATE_HZ = "rate";
    public static final String KEY_FRAME_RATE_HZ = "framerate";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_EYE_TRACKING = "eye";
    public static final String KEY_FACE_TRACKING = "face";
    // Forward regardless of the foreground app (ignores the gate list)
    public static final String KEY_ALWAYS_FORWARD = "always";
    // Comma separated package names; null or empty means no gate apps (--esn gate)
    public static final String KEY_GATE_PACKAGES = "gate";

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_RATE = "rate_hz";
    private static final String PREF_AUTOSTART = "autostart";
    private static final String PREF_FRAME_RATE = "frame_rate_hz";
    private static final String PREF_EYE_TRACKING = "eye_tracking";
    private static final String PREF_FACE_TRACKING = "face_tracking";
    private static final String PREF_ALWAYS_FORWARD = "always_forward";
    private static final String PREF_GATE_PACKAGES = "gate_packages";
    // Single package of versions before the gate list
    private static final String PREF_GATE_PACKAGE_LEGACY = "gate_package";
    private static final String PACKAGE_LIST_SEPARATOR = ",";

    /** One consistent snapshot of all settings. */
    public static final class Values {
        public String host = "";
        public int port = DEFAULT_PORT;
        public float rateHz = DEFAULT_RATE_HZ;
        public float frameRateHz = DEFAULT_FRAME_RATE_HZ;
        public boolean autostart = false;
        public boolean eyeTracking = true;
        public boolean faceTracking = true;
        public boolean alwaysForward = false;
        public List<String> gatePackages = new ArrayList<>(DEFAULT_GATE_PACKAGES);

        /** Whether the bridge runs regardless of the foreground app. */
        public boolean runsAlways() {
            return alwaysForward || gatePackages.isEmpty();
        }

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
            eyeTracking = extras.getBoolean(KEY_EYE_TRACKING, eyeTracking);
            faceTracking = extras.getBoolean(KEY_FACE_TRACKING, faceTracking);
            alwaysForward = extras.getBoolean(KEY_ALWAYS_FORWARD, alwaysForward);
            if (extras.containsKey(KEY_GATE_PACKAGES)) {
                gatePackages = parsePackageList(extras.getString(KEY_GATE_PACKAGES));
            }
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putString(KEY_HOST, host);
            bundle.putInt(KEY_PORT, port);
            bundle.putFloat(KEY_RATE_HZ, rateHz);
            bundle.putFloat(KEY_FRAME_RATE_HZ, frameRateHz);
            bundle.putBoolean(KEY_AUTOSTART, autostart);
            bundle.putBoolean(KEY_EYE_TRACKING, eyeTracking);
            bundle.putBoolean(KEY_FACE_TRACKING, faceTracking);
            bundle.putBoolean(KEY_ALWAYS_FORWARD, alwaysForward);
            bundle.putString(KEY_GATE_PACKAGES, joinPackageList(gatePackages));
            return bundle;
        }

        private static String trimmed(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** Splits a comma separated list of package names; blanks are dropped. */
    public static List<String> parsePackageList(String text) {
        List<String> packages = new ArrayList<>();
        if (text == null) {
            return packages;
        }
        for (String item : text.split(PACKAGE_LIST_SEPARATOR)) {
            String name = item.trim();
            if (!name.isEmpty() && !packages.contains(name)) {
                packages.add(name);
            }
        }
        return packages;
    }

    public static String joinPackageList(List<String> packages) {
        return String.join(PACKAGE_LIST_SEPARATOR, packages);
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
        values.eyeTracking = preferences.getBoolean(PREF_EYE_TRACKING, values.eyeTracking);
        values.faceTracking = preferences.getBoolean(PREF_FACE_TRACKING, values.faceTracking);
        values.alwaysForward = preferences.getBoolean(PREF_ALWAYS_FORWARD, values.alwaysForward);
        String gatePackages = preferences.getString(PREF_GATE_PACKAGES,
                preferences.getString(PREF_GATE_PACKAGE_LEGACY, joinPackageList(values.gatePackages)));
        values.gatePackages = parsePackageList(gatePackages);
        return values;
    }

    public void store(Values values) {
        preferences.edit()
                .putString(PREF_HOST, values.host)
                .putInt(PREF_PORT, values.port)
                .putFloat(PREF_RATE, values.rateHz)
                .putFloat(PREF_FRAME_RATE, values.frameRateHz)
                .putBoolean(PREF_AUTOSTART, values.autostart)
                .putBoolean(PREF_EYE_TRACKING, values.eyeTracking)
                .putBoolean(PREF_FACE_TRACKING, values.faceTracking)
                .putBoolean(PREF_ALWAYS_FORWARD, values.alwaysForward)
                .putString(PREF_GATE_PACKAGES, joinPackageList(values.gatePackages))
                .apply();
    }
}

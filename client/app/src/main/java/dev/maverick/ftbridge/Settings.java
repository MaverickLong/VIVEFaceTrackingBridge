package dev.maverick.ftbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import dev.maverick.ftbridge.control.ControlProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent configuration. {@link Values} is the in-memory form; its bundle keys
 * (ControlProtocol.KEY_*) double as the extras of the START intent (adb provisioning) and
 * as the payload of the control interface (settings app).
 */
public final class Settings {
    public static final int DEFAULT_PORT = ControlProtocol.DEFAULT_PORT;
    public static final float DEFAULT_RATE_HZ = ControlProtocol.DEFAULT_RATE_HZ;
    public static final float DEFAULT_FRAME_RATE_HZ = ControlProtocol.DEFAULT_FRAME_RATE_HZ;
    public static final List<String> DEFAULT_GATE_PACKAGES =
            Collections.singletonList(ControlProtocol.VIRTUAL_DESKTOP_PACKAGE);

    private static final String PREFERENCES_NAME = "ftbridge";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_RATE = "rate_hz";
    private static final String PREF_AUTOSTART = "autostart";
    private static final String PREF_FRAME_RATE = "frame_rate_hz";
    private static final String PREF_GAZE_TRACKING = "gaze_tracking";
    private static final String PREF_EYE_TRACKING = "eye_tracking";
    private static final String PREF_FACE_TRACKING = "face_tracking";
    private static final String PREF_ALWAYS_FORWARD = "always_forward";
    private static final String PREF_GATE_PACKAGES = "gate_packages";
    // Single package of versions before the gate list
    private static final String PREF_GATE_PACKAGE_LEGACY = "gate_package";

    /** One consistent snapshot of all settings. */
    public static final class Values {
        public String host = "";
        public int port = DEFAULT_PORT;
        public float rateHz = DEFAULT_RATE_HZ;
        public float frameRateHz = DEFAULT_FRAME_RATE_HZ;
        public boolean autostart = false;
        public boolean gazeTracking = false;
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
            if (extras.containsKey(ControlProtocol.KEY_HOST)) {
                host = trimmed(extras.getString(ControlProtocol.KEY_HOST));
            }
            port = extras.getInt(ControlProtocol.KEY_PORT, port);
            rateHz = extras.getFloat(ControlProtocol.KEY_RATE_HZ, rateHz);
            frameRateHz = extras.getFloat(ControlProtocol.KEY_FRAME_RATE_HZ, frameRateHz);
            autostart = extras.getBoolean(ControlProtocol.KEY_AUTOSTART, autostart);
            gazeTracking = extras.getBoolean(ControlProtocol.KEY_GAZE_TRACKING, gazeTracking);
            eyeTracking = extras.getBoolean(ControlProtocol.KEY_EYE_TRACKING, eyeTracking);
            faceTracking = extras.getBoolean(ControlProtocol.KEY_FACE_TRACKING, faceTracking);
            alwaysForward = extras.getBoolean(ControlProtocol.KEY_ALWAYS_FORWARD, alwaysForward);
            if (extras.containsKey(ControlProtocol.KEY_GATE_PACKAGES)) {
                gatePackages = ControlProtocol.parsePackageList(
                        extras.getString(ControlProtocol.KEY_GATE_PACKAGES));
            }
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putString(ControlProtocol.KEY_HOST, host);
            bundle.putInt(ControlProtocol.KEY_PORT, port);
            bundle.putFloat(ControlProtocol.KEY_RATE_HZ, rateHz);
            bundle.putFloat(ControlProtocol.KEY_FRAME_RATE_HZ, frameRateHz);
            bundle.putBoolean(ControlProtocol.KEY_AUTOSTART, autostart);
            bundle.putBoolean(ControlProtocol.KEY_GAZE_TRACKING, gazeTracking);
            bundle.putBoolean(ControlProtocol.KEY_EYE_TRACKING, eyeTracking);
            bundle.putBoolean(ControlProtocol.KEY_FACE_TRACKING, faceTracking);
            bundle.putBoolean(ControlProtocol.KEY_ALWAYS_FORWARD, alwaysForward);
            bundle.putString(ControlProtocol.KEY_GATE_PACKAGES,
                    ControlProtocol.joinPackageList(gatePackages));
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
        values.gazeTracking = preferences.getBoolean(PREF_GAZE_TRACKING, values.gazeTracking);
        values.eyeTracking = preferences.getBoolean(PREF_EYE_TRACKING, values.eyeTracking);
        values.faceTracking = preferences.getBoolean(PREF_FACE_TRACKING, values.faceTracking);
        values.alwaysForward = preferences.getBoolean(PREF_ALWAYS_FORWARD, values.alwaysForward);
        String gatePackages = preferences.getString(PREF_GATE_PACKAGES,
                preferences.getString(PREF_GATE_PACKAGE_LEGACY,
                        ControlProtocol.joinPackageList(values.gatePackages)));
        values.gatePackages = ControlProtocol.parsePackageList(gatePackages);
        return values;
    }

    public void store(Values values) {
        preferences.edit()
                .putString(PREF_HOST, values.host)
                .putInt(PREF_PORT, values.port)
                .putFloat(PREF_RATE, values.rateHz)
                .putFloat(PREF_FRAME_RATE, values.frameRateHz)
                .putBoolean(PREF_AUTOSTART, values.autostart)
                .putBoolean(PREF_GAZE_TRACKING, values.gazeTracking)
                .putBoolean(PREF_EYE_TRACKING, values.eyeTracking)
                .putBoolean(PREF_FACE_TRACKING, values.faceTracking)
                .putBoolean(PREF_ALWAYS_FORWARD, values.alwaysForward)
                .putString(PREF_GATE_PACKAGES, ControlProtocol.joinPackageList(values.gatePackages))
                .apply();
    }
}

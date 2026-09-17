package dev.maverick.ftbridge.control;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract between the FT Bridge service app and the settings app: component names, the
 * Messenger protocol of the control service and the setting keys, which are also the
 * extras of the service's START intent.
 */
public final class ControlProtocol {
    public static final String SERVICE_PACKAGE = "dev.maverick.ftbridge";
    public static final String CONTROL_SERVICE_CLASS = "dev.maverick.ftbridge.ControlService";
    public static final String SETTINGS_PACKAGE = "dev.maverick.ftbridge.settings";
    public static final String SETTINGS_ACTIVITY_CLASS = "dev.maverick.ftbridge.settings.SettingsActivity";
    // Signature protection level: both apps are signed with the same key
    public static final String CONTROL_PERMISSION = "dev.maverick.ftbridge.permission.CONTROL";

    // Message.what of the control service
    /** Request the current settings and status; answered with {@link #MSG_STATE}. */
    public static final int MSG_GET_STATE = 1;
    /** Apply the settings in the data bundle and (re)start the service; answered with MSG_STATE. */
    public static final int MSG_APPLY = 2;
    /** Stop the tracking service; answered with MSG_STATE. */
    public static final int MSG_STOP = 3;
    /** Reply: data bundle = settings plus the status keys. */
    public static final int MSG_STATE = 4;

    // Settings keys, e.g.:
    //   adb shell am start-foreground-service -n dev.maverick.ftbridge/.TrackingService \
    //       -a dev.maverick.ftbridge.START --es host 192.168.1.10 --es gate VirtualDesktop.Android
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_RATE_HZ = "rate";
    public static final String KEY_FRAME_RATE_HZ = "framerate";
    public static final String KEY_AUTOSTART = "autostart";
    // HTC eye expressions (blink, wide, squeeze, look direction), Meta social gaze
    public static final String KEY_EYE_TRACKING = "eye";
    // VIVE, with eye tracking on: also send XR_HTC_eye_tracker's per-eye gaze and pupil
    // diameter. Only the VRCFT-ViveBridge module understands that segment.
    public static final String KEY_PRECISE_EYE = "preciseeye";
    public static final String KEY_FACE_TRACKING = "face";
    // Forward regardless of the foreground app (ignores the gate list)
    public static final String KEY_ALWAYS_FORWARD = "always";
    // Comma separated package names; null or empty means no gate apps (--esn gate)
    public static final String KEY_GATE_PACKAGES = "gate";

    // Status keys of MSG_STATE replies
    public static final String KEY_SERVICE_RUNNING = "service_running";
    /** JSON snapshot of the bridge, see core/src/status.rs. */
    public static final String KEY_BRIDGE_STATUS = "bridge_status";
    public static final String KEY_GATE_STATUS = "gate_status";
    public static final String KEY_USAGE_ACCESS = "usage_access";
    public static final String KEY_VERSION = "version";

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

    private static final String PACKAGE_LIST_SEPARATOR = ",";

    private ControlProtocol() {}

    /** Splits a comma separated list of package names; blanks and duplicates are dropped. */
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
}

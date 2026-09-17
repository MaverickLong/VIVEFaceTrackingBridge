package dev.maverick.ftbridge;

import android.content.Context;

/** JNI surface of the Rust core (client/core). */
public final class NativeCore {
    static {
        System.loadLibrary("ftbridge_core");
    }

    private NativeCore() {}

    /**
     * Starts the bridge thread. No-op if it is already running.
     *
     * @param context     passed to the OpenXR runtime as the application activity
     * @param frameRateHz rate of the empty frames keeping the session running, 0 for none
     *                    (see core/src/bridge.rs)
     * @param gazeTracking use the gaze pose sources (XR_EXT_eye_gaze_interaction, Meta social
     *                     eye tracking); off leaves the eye tracker's gaze to the streaming app
     * @param eyeTracking  use the HTC eye expression tracker (blink, wide, squeeze, direction)
     * @param faceTracking use the face trackers (HTC lip, Meta, Pico expressions)
     * @return true if the bridge is running afterwards; false if a previous instance is still
     *         shutting down (retry later) or the arguments were rejected (see status())
     */
    public static native boolean start(Context context, String host, int port, float rateHz,
                                       float frameRateHz, boolean gazeTracking, boolean eyeTracking,
                                       boolean faceTracking);

    /** Requests the bridge thread to stop; returns immediately. */
    public static native void stop();

    /** JSON snapshot of the bridge state, see core/src/status.rs. */
    public static native String status();
}

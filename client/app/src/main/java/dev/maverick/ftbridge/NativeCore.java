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
     * @param eyeTracking  use the HTC eye expression tracker (blink, wide, squeeze, direction)
     *                     and Meta's social eye gaze
     * @param preciseEye   with eye tracking on VIVE: also send XR_HTC_eye_tracker's per-eye gaze
     *                     and pupil diameter (needs the VRCFT-ViveBridge module on the PC)
     * @param faceTracking use the face trackers (HTC lip, Meta, Pico expressions)
     * @param eyeProbe     diagnostics: also poll XR_HTC_eye_tracker and log its samples as CSV
     *                     lines (see core/src/probe.rs and tools/probe-eye-tracker.ps1)
     * @return true if the bridge is running afterwards; false if a previous instance is still
     *         shutting down (retry later) or the arguments were rejected (see status())
     */
    public static native boolean start(Context context, String host, int port, float rateHz,
                                       float frameRateHz, boolean eyeTracking, boolean preciseEye,
                                       boolean faceTracking, boolean eyeProbe);

    /** Requests the bridge thread to stop; returns immediately. */
    public static native void stop();

    /** JSON snapshot of the bridge state, see core/src/status.rs. */
    public static native String status();
}

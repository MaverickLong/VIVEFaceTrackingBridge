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
     */
    public static native void start(Context context, String host, int port, float rateHz, float frameRateHz);

    /** Requests the bridge thread to stop; returns immediately. */
    public static native void stop();

    /** JSON snapshot of the bridge state, see core/src/status.rs. */
    public static native String status();
}

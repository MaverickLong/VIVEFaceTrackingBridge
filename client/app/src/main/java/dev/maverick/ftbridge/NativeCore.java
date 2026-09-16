package dev.maverick.ftbridge;

import android.content.Context;

/** JNI surface of the Rust core (client/core). */
public final class NativeCore {
    static {
        System.loadLibrary("ftbridge_core");
    }

    private NativeCore() {}

    /** Starts the bridge thread. No-op if it is already running. */
    public static native void start(Context context, String host, int port, float rateHz);

    /** Requests the bridge thread to stop; returns immediately. */
    public static native void stop();

    /** JSON snapshot of the bridge state, see core/src/status.rs. */
    public static native String status();
}

package dev.maverick.ftbridge.companion;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Typed VRConfig access over Companion command 192. Does not own/close its caller. */
public final class VrConfigClient {
    public static final int COMMAND_TYPE = 192;
    private final CommandCaller caller;
    private final AtomicInteger sequence = new AtomicInteger();

    public VrConfigClient(CommandCaller caller) { this.caller = Objects.requireNonNull(caller, "caller"); }

    public CompletableFuture<String> get(String key) { return exchange(key, null); }

    /** Writes and reads back; fails on a mismatch. No rollback or automatic retry. */
    public CompletableFuture<String> set(String key, String value) {
        Objects.requireNonNull(value, "value");
        return exchange(key, value).thenCompose(ignored -> get(key)).thenApply(actual -> {
            if (!value.equals(actual)) throw new CompletionException(new IOException("VRConfig readback mismatch for " + key));
            return actual;
        });
    }

    /** Accepts only true/false (case insensitive); unknown/missing values fail. */
    public CompletableFuture<Boolean> getBoolean(String key) { return get(key).thenApply(VrConfigClient::booleanValue); }

    /** Encodes booleans as true/false, never 1/0, and verifies the stored value. */
    public CompletableFuture<Boolean> setBoolean(String key, boolean enabled) {
        return set(key, Boolean.toString(enabled)).thenApply(VrConfigClient::booleanValue);
    }

    private CompletableFuture<String> exchange(String key, String value) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) throw new IllegalArgumentException("VRConfig key must not be empty");
        int id = sequence.updateAndGet(previous -> previous == Integer.MAX_VALUE ? 1 : previous + 1);
        return caller.call(COMMAND_TYPE, VrConfigProtocol.request(id, key, value)).thenApply(bytes -> {
            try { return VrConfigProtocol.response(bytes, id, key, value != null); }
            catch (IOException e) { throw new CompletionException(e); }
        });
    }

    private static boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new CompletionException(new IOException("VRConfig value is not a boolean"));
    }
}

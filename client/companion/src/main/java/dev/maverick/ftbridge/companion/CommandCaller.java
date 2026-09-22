package dev.maverick.ftbridge.companion;

import java.util.concurrent.CompletableFuture;

/** Asynchronous command transport; payload and response use the command's protobuf schema. */
@FunctionalInterface
public interface CommandCaller {
    /** Does not retry commands. A failed/timed-out write may already have been applied. */
    CompletableFuture<byte[]> call(int commandType, byte[] payload);
}

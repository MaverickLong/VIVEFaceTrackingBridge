package dev.maverick.ftbridge.companion;

/** App-private IPC. This contract is not part of the library's public API. */
final class BridgeProtocol {
    static final int CALL = 1, RESULT = 2;
    static final String PAYLOAD = "payload", ERROR = "error", REASON = "reason", CODE = "code";
    static final String DEADLINE = "deadline";
    static final int MAX_PAYLOAD = 128 * 1024;
    static final int MAX_PENDING = 32;
    static final long TIMEOUT_MS = 15000;

    private BridgeProtocol() {}
}

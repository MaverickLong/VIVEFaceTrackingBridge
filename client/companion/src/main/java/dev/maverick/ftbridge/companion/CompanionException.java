package dev.maverick.ftbridge.companion;

import java.io.IOException;

/** Transport failure. Command-specific protobuf errors are decoded by the caller. */
public final class CompanionException extends IOException {
    public enum Reason { UNAVAILABLE, DISCONNECTED, TIMEOUT, CLOSED, REJECTED, TRANSPORT }

    private final Reason reason;
    private final int vendorCode;

    public CompanionException(Reason reason, String message) { this(reason, message, 0); }

    public CompanionException(Reason reason, String message, int vendorCode) {
        super(message);
        this.reason = reason;
        this.vendorCode = vendorCode;
    }

    public Reason getReason() { return reason; }
    /** HTC onFailure code, or zero when the failure was generated locally. */
    public int getVendorCode() { return vendorCode; }
}

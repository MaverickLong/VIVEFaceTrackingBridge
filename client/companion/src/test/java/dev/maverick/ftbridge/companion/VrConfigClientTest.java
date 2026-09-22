package dev.maverick.ftbridge.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Standalone JVM checks, run by :companion:testProtocol without Android or a headset. */
public final class VrConfigClientTest {
    private static final String EYE = "eye_wide_feature_enable";
    private static int checks;
    private interface Checked { void run() throws Exception; }

    private static void check(boolean valid) {
        if (!valid) throw new AssertionError("Check " + (checks + 1));
        checks++;
    }

    private static void rejects(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); }
        catch (Exception e) {
            Throwable cause = e;
            while (cause instanceof CompletionException) cause = cause.getCause();
            if (!type.isInstance(cause)) throw new AssertionError("Unexpected failure", cause);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class FakeCaller implements CommandCaller {
        final Queue<CompletableFuture<byte[]>> replies = new ArrayDeque<>();
        final List<byte[]> requests = new ArrayList<>();
        void add(byte[] response) { replies.add(CompletableFuture.completedFuture(response)); }
        @Override public CompletableFuture<byte[]> call(int command, byte[] payload) {
            check(command == 192);
            requests.add(payload);
            if (replies.isEmpty()) throw new AssertionError("Unexpected extra call");
            return replies.remove();
        }
    }

    // Independent fixture builder; include omitted proto3 defaults in captured fixtures below.
    private static void number(ByteArrayOutputStream out, int n) {
        while ((n & ~127) != 0) { out.write((n & 127) | 128); n >>>= 7; }
        out.write(n);
    }
    private static void text(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        number(out, (field << 3) | 2); number(out, bytes.length); out.write(bytes, 0, bytes.length);
    }
    private static byte[] response(int id, boolean write, String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(8); number(out, id);
        if (write) { out.write(24); out.write(1); }
        text(out, 4, key);
        if (value != null) text(out, 5, value);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        byte[] capturedRead = Base64.getDecoder().decode("CAEiF2V5ZV93aWRlX2ZlYXR1cmVfZW5hYmxlKgVmYWxzZQ==");
        byte[] capturedSet = Base64.getDecoder().decode("CAIYASIXZXllX3dpZGVfZmVhdHVyZV9lbmFibGUqBHRydWU=");
        check("false".equals(VrConfigProtocol.response(capturedRead, 1, EYE, false)));
        check("true".equals(VrConfigProtocol.response(capturedSet, 2, EYE, true)));
        check(Arrays.equals(VrConfigProtocol.request(1, EYE, null),
                Base64.getDecoder().decode("CAEQABoXZXllX3dpZGVfZmVhdHVyZV9lbmFibGU=")));
        check(Arrays.equals(VrConfigProtocol.request(2, EYE, "true"),
                Base64.getDecoder().decode("CAIQARoXZXllX3dpZGVfZmVhdHVyZV9lbmFibGUiBHRydWU=")));
        byte[] multiByteId = VrConfigProtocol.request(128, "k", "");
        check((multiByteId[1] & 255) == 128 && multiByteId[2] == 1);
        check("".equals(VrConfigProtocol.response(response(128, false, "k", null), 128, "k", false)));
        rejects(IOException.class, () -> VrConfigProtocol.response(capturedRead, 2, EYE, false));
        rejects(IOException.class, () -> VrConfigProtocol.response(capturedRead, 1, EYE, true));
        rejects(IOException.class, () -> VrConfigProtocol.response(capturedRead, 1, "other-key", false));
        rejects(IOException.class, () -> VrConfigProtocol.response(Arrays.copyOf(capturedRead, capturedRead.length - 1), 1, EYE, false));
        rejects(IOException.class, () -> VrConfigProtocol.response(null, 1, EYE, false));
        rejects(IOException.class, () -> VrConfigProtocol.response(new byte[]{8, (byte) 128}, 1, EYE, false));
        rejects(IOException.class, () -> VrConfigProtocol.response(new byte[]{0}, 1, EYE, false));
        byte[] futureField = Arrays.copyOf(capturedRead, capturedRead.length + 3);
        futureField[capturedRead.length] = 80;
        futureField[capturedRead.length + 1] = (byte) 128;
        futureField[capturedRead.length + 2] = 1;
        check("false".equals(VrConfigProtocol.response(futureField, 1, EYE, false)));

        FakeCaller read = new FakeCaller(); read.add(capturedRead);
        check(!new VrConfigClient(read).getBoolean(EYE).join());

        FakeCaller write = new FakeCaller();
        write.add(response(1, true, EYE, "true")); write.add(response(2, false, EYE, "true"));
        check(new VrConfigClient(write).setBoolean(EYE, true).join());
        check(write.requests.size() == 2);
        check(Arrays.equals(write.requests.get(1), VrConfigProtocol.request(2, EYE, null)));

        FakeCaller mismatch = new FakeCaller();
        mismatch.add(response(1, true, EYE, "true")); mismatch.add(response(2, false, EYE, "false"));
        rejects(IOException.class, () -> new VrConfigClient(mismatch).setBoolean(EYE, true).join());

        String key = "oemc_usb_autogrant_rules", json = "[{\"packages\":[\"应用\"]}]";
        FakeCaller generic = new FakeCaller();
        generic.add(response(1, true, key, json)); generic.add(response(2, false, key, json));
        check(json.equals(new VrConfigClient(generic).set(key, json).join()));
        check(Arrays.equals(generic.requests.get(0), VrConfigProtocol.request(1, key, json)));

        FakeCaller invalidBoolean = new FakeCaller(); invalidBoolean.add(response(1, false, EYE, "1"));
        rejects(IOException.class, () -> new VrConfigClient(invalidBoolean).getBoolean(EYE).join());
        FakeCaller uppercase = new FakeCaller(); uppercase.add(response(1, false, EYE, "True"));
        check(new VrConfigClient(uppercase).getBoolean(EYE).join());

        FakeCaller rejected = new FakeCaller();
        byte[] error = response(1, true, EYE, "true");
        error = Arrays.copyOf(error, error.length + 2); error[error.length - 2] = 16; error[error.length - 1] = 1;
        rejected.add(error);
        rejects(IOException.class, () -> new VrConfigClient(rejected).setBoolean(EYE, true).join());
        check(rejected.requests.size() == 1);

        FakeCaller transport = new FakeCaller();
        CompletableFuture<byte[]> failed = new CompletableFuture<>(); failed.completeExceptionally(new IOException("disconnected"));
        transport.replies.add(failed);
        rejects(IOException.class, () -> new VrConfigClient(transport).setBoolean(EYE, false).join());
        check(transport.requests.size() == 1);
        rejects(IllegalArgumentException.class, () -> new VrConfigClient(transport).get(""));
        rejects(NullPointerException.class, () -> new VrConfigClient(transport).set("key", null));
        System.out.println("Passed " + checks + " Companion VRConfig checks");
    }
}

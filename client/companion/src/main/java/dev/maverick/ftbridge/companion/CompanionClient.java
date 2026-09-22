package dev.maverick.ftbridge.companion;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns an app-private connection to the Companion helper process. Binding is lazy.
 * Calls may originate on any thread; do not block the main thread on their futures.
 * Close when the owner stops. Closing fails pending calls but cannot undo a write.
 */
public final class CompanionClient implements CommandCaller, AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final AtomicBoolean closed = new AtomicBoolean();
    // All mutable connection/request state below is confined to the main thread.
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private Messenger remote;
    private boolean bound;
    private int nextId;

    private final class Pending {
        final int id, command;
        final byte[] payload;
        final CompletableFuture<byte[]> future;
        final long deadline = SystemClock.elapsedRealtime() + BridgeProtocol.TIMEOUT_MS;
        final Runnable timeout;
        boolean sent;

        Pending(int id, int command, byte[] payload, CompletableFuture<byte[]> future) {
            this.id = id;
            this.command = command;
            this.payload = payload;
            this.future = future;
            timeout = () -> finish(id, null, new CompanionException(
                    CompanionException.Reason.TIMEOUT, "HTC request timed out; read back before retrying a write"));
        }
    }

    public CompanionClient(Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    @Override
    public CompletableFuture<byte[]> call(int commandType, byte[] payload) {
        if (commandType <= 0) throw new IllegalArgumentException("commandType must be positive");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > BridgeProtocol.MAX_PAYLOAD) throw new IllegalArgumentException("Payload exceeds 128 KiB");
        byte[] copy = payload.clone();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        main.post(() -> {
            if (closed.get()) {
                future.completeExceptionally(new CompanionException(CompanionException.Reason.CLOSED, "Companion client is closed"));
                return;
            }
            if (future.isCancelled()) return;
            if (pending.size() >= BridgeProtocol.MAX_PENDING) {
                future.completeExceptionally(new CompanionException(CompanionException.Reason.TRANSPORT, "Too many pending HTC requests"));
                return;
            }
            if (nextId == Integer.MAX_VALUE) nextId = 0;
            while (pending.containsKey(++nextId)) { /* reserve a unique local id */ }
            Pending request = new Pending(nextId, commandType, copy, future);
            pending.put(request.id, request);
            main.postDelayed(request.timeout, BridgeProtocol.TIMEOUT_MS);
            future.whenComplete((value, error) -> {
                if (future.isCancelled()) main.post(() -> finish(request.id, null,
                        new CompanionException(CompanionException.Reason.CLOSED, "Request cancelled")));
            });
            if (remote != null) send(request);
            else if (!bound) bind();
        });
        return future;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed.get()) { release(); return; }
            remote = new Messenger(service);
            for (Pending request : new ArrayList<>(pending.values())) {
                if (!request.sent && pending.containsKey(request.id)) send(request);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            failAll(new CompanionException(CompanionException.Reason.DISCONNECTED, "Companion helper disconnected"));
        }

        @Override
        public void onNullBinding(ComponentName name) { unavailable("Companion helper is unavailable"); }
        @Override
        public void onBindingDied(ComponentName name) { unavailable("Companion helper changed; retry the connection"); }
    };

    private void bind() {
        try {
            bound = context.bindService(new Intent(context, CompanionBridgeService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) unavailable("Companion helper is unavailable");
        } catch (SecurityException e) {
            unavailable("Companion helper access denied");
        }
    }

    private void unavailable(String message) {
        release();
        failAll(new CompanionException(CompanionException.Reason.UNAVAILABLE, message));
    }

    private void send(Pending request) {
        if (request.future.isCancelled()) return;
        Message message = Message.obtain(null, BridgeProtocol.CALL);
        message.arg1 = request.id;
        message.arg2 = request.command;
        message.replyTo = replies;
        Bundle data = new Bundle();
        data.putByteArray(BridgeProtocol.PAYLOAD, request.payload);
        data.putLong(BridgeProtocol.DEADLINE, request.deadline);
        message.setData(data);
        try {
            request.sent = true;
            remote.send(message);
        } catch (RemoteException e) {
            unavailable("Companion helper is not responding");
        }
    }

    private boolean receive(Message message) {
        if (message.what != BridgeProtocol.RESULT) return false;
        Pending request = pending.get(message.arg1);
        if (request == null || request.command != message.arg2) return true;
        Bundle data = message.getData();
        if (data.containsKey(BridgeProtocol.ERROR)) {
            CompanionException.Reason reason;
            try { reason = CompanionException.Reason.valueOf(data.getString(BridgeProtocol.REASON, "TRANSPORT")); }
            catch (IllegalArgumentException e) { reason = CompanionException.Reason.TRANSPORT; }
            finish(request.id, null, new CompanionException(reason, data.getString(BridgeProtocol.ERROR), data.getInt(BridgeProtocol.CODE)));
        } else {
            byte[] bytes = data.getByteArray(BridgeProtocol.PAYLOAD);
            finish(request.id, bytes, bytes == null ? new CompanionException(
                    CompanionException.Reason.TRANSPORT, "Missing Companion response") : null);
        }
        return true;
    }

    private void finish(int id, byte[] value, Throwable error) {
        Pending request = pending.remove(id);
        if (request == null) return;
        main.removeCallbacks(request.timeout);
        if (error == null) request.future.complete(value);
        else request.future.completeExceptionally(error);
    }

    private void failAll(Throwable error) {
        for (int id : new ArrayList<>(pending.keySet())) finish(id, null, error);
    }

    private void release() {
        remote = null;
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        main.post(() -> {
            release();
            failAll(new CompanionException(CompanionException.Reason.CLOSED, "Companion client is closed"));
        });
    }
}

package dev.maverick.ftbridge.companion;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Manifest entry point; use CompanionClient rather than binding to this implementation. */
public final class CompanionBridgeService extends Service {
    private static final String HELPER_PROCESS = "com.htc.gatthmdservice";
    private static final String SERVICE = "com.htc.companionserviceapi.ICompanionService";
    private static final String CALLBACK = "com.htc.companionserviceapi.ICompanionServiceCallback";
    private final Object lock = new Object();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(BridgeProtocol.MAX_PENDING));
    private final BlockingQueue<Response> responses = new ArrayBlockingQueue<>(1);
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));
    private IBinder companion, registeredBinder;
    private String connectionError;
    private boolean bound;
    private int sequence;
    private volatile int activeId, activeCommand;

    private static final class Response {
        int id, command, failure;
        boolean failed;
        byte[] payload;
        CompanionException error;
    }

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK);
                return true;
            }
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CALLBACK);
            Response response = new Response();
            response.id = data.readInt();
            response.command = data.readInt();
            if (code == 1) {
                response.failed = true;
                response.failure = data.readInt();
            }
            else response.payload = data.createByteArray();
            // Drop unsolicited events, late replies and replies for other commands.
            if (response.id > 0 && response.id == activeId && response.command == activeCommand) responses.offer(response);
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                companion = binder;
                connectionError = null;
                lock.notifyAll();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { disconnected("HTC service disconnected"); }
        @Override
        public void onNullBinding(ComponentName name) { disconnected("HTC service is unavailable"); }
        @Override
        public void onBindingDied(ComponentName name) { disconnected("HTC service changed; close and reopen the client"); }
    };

    private void disconnected(String error) {
        synchronized (lock) {
            companion = null;
            connectionError = error;
            lock.notifyAll();
        }
        Response response = new Response();
        response.id = activeId;
        response.command = activeCommand;
        response.error = new CompanionException(CompanionException.Reason.DISCONNECTED, error);
        responses.offer(response);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!HELPER_PROCESS.equals(android.app.Application.getProcessName())) {
            disconnected("Companion helper requires its dedicated manifest process");
            return;
        }
        Intent intent = new Intent().setComponent(new ComponentName(
                "com.htc.gatthmdservice", "com.htc.companionservice.CompanionService"));
        try {
            bound = bindService(intent, connection, BIND_AUTO_CREATE);
            if (!bound) disconnected("A compatible HTC CompanionService is not installed");
        } catch (SecurityException e) { disconnected("HTC service access denied"); }
    }

    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private boolean handle(Message message) {
        if (message.what != BridgeProtocol.CALL) return false;
        if (message.sendingUid != android.os.Process.myUid() || message.replyTo == null) return true;
        final Messenger target = message.replyTo;
        final int requestId = message.arg1, command = message.arg2;
        final byte[] payload = message.getData().getByteArray(BridgeProtocol.PAYLOAD);
        final long deadline = Math.min(message.getData().getLong(BridgeProtocol.DEADLINE),
                SystemClock.elapsedRealtime() + BridgeProtocol.TIMEOUT_MS);
        if (command <= 0 || payload == null || payload.length > BridgeProtocol.MAX_PAYLOAD) {
            reply(target, requestId, command, null, new CompanionException(CompanionException.Reason.TRANSPORT, "Invalid Companion request"));
            return true;
        }
        try {
            worker.execute(() -> {
                try { reply(target, requestId, command, request(command, payload, deadline), null); }
                catch (Exception e) { reply(target, requestId, command, null, e); }
            });
        } catch (RejectedExecutionException e) {
            reply(target, requestId, command, null, new CompanionException(CompanionException.Reason.TRANSPORT, "Companion request queue is full"));
        }
        return true;
    }

    private void reply(Messenger target, int id, int command, byte[] payload, Exception error) {
        Bundle data = new Bundle();
        if (error == null) data.putByteArray(BridgeProtocol.PAYLOAD, payload);
        else {
            CompanionException failure = error instanceof CompanionException ? (CompanionException) error
                    : new CompanionException(CompanionException.Reason.TRANSPORT, "HTC transport failed: " + error.getClass().getSimpleName());
            data.putString(BridgeProtocol.ERROR, failure.getMessage());
            data.putString(BridgeProtocol.REASON, failure.getReason().name());
            data.putInt(BridgeProtocol.CODE, failure.getVendorCode());
        }
        Message message = Message.obtain(null, BridgeProtocol.RESULT);
        message.arg1 = id;
        message.arg2 = command;
        message.setData(data);
        try { target.send(message); }
        catch (RemoteException ignored) { /* The owner closed; never replay the command. */ }
    }

    private long remaining(long deadline) throws CompanionException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new CompanionException(CompanionException.Reason.TIMEOUT, "HTC request timed out; read back before retrying a write");
        return remaining;
    }

    private IBinder connected(long deadline) throws Exception {
        IBinder binder;
        synchronized (lock) {
            while (companion == null && connectionError == null) lock.wait(remaining(deadline));
            if (companion == null) throw new CompanionException(CompanionException.Reason.UNAVAILABLE, connectionError);
            binder = companion;
        }
        remaining(deadline);
        if (registeredBinder != binder) {
            transact(binder, 1, data -> data.writeStrongBinder(callback));
            registeredBinder = binder;
        }
        return binder;
    }

    private byte[] request(int command, byte[] payload, long deadline) throws Exception {
        remaining(deadline); // Expired queued commands must not execute later.
        IBinder binder = connected(deadline);
        if (sequence == Integer.MAX_VALUE) sequence = 0;
        int id = ++sequence;
        responses.clear();
        activeCommand = command;
        activeId = id;
        try {
            remaining(deadline);
            transact(binder, 2, data -> {
                data.writeInt(id);
                data.writeInt(command);
                data.writeByteArray(payload);
            });
            while (true) {
                Response response = responses.poll(remaining(deadline), TimeUnit.MILLISECONDS);
                if (response == null) throw new CompanionException(CompanionException.Reason.TIMEOUT, "HTC response timed out");
                if (response.id != id || response.command != command) continue;
                if (response.error != null) throw response.error;
                if (response.failed) throw new CompanionException(CompanionException.Reason.REJECTED,
                        "HTC rejected command " + command + " (code " + response.failure + ")", response.failure);
                if (response.payload == null || response.payload.length > BridgeProtocol.MAX_PAYLOAD) {
                    throw new CompanionException(CompanionException.Reason.TRANSPORT, "Invalid HTC response payload");
                }
                return response.payload;
            }
        } finally { activeId = 0; }
    }

    private interface ParcelWriter { void write(Parcel data); }

    private void transact(IBinder binder, int code, ParcelWriter writer) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE);
            writer.write(data);
            if (!binder.transact(code, data, reply, 0)) throw new CompanionException(
                    CompanionException.Reason.TRANSPORT, "Unsupported HTC service interface");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        if (bound) unbindService(connection);
        super.onDestroy();
        // The library manifest reserves a dedicated process. HTC has no callback
        // unregister operation; ending OUR process releases its callback/death link.
        if (HELPER_PROCESS.equals(android.app.Application.getProcessName())) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}

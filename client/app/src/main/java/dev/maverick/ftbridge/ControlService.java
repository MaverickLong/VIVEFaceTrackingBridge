package dev.maverick.ftbridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import dev.maverick.ftbridge.control.ControlProtocol;

/**
 * Control interface for the settings app, a Messenger service guarded by a signature
 * permission (both apps are signed with the same key). Protocol: {@link ControlProtocol}.
 *
 * The settings app lives in its own package because the VIVE system force-stops every
 * package with a launchable activity whenever a VR app gains focus. This package has no
 * activity, so the bridge survives; the settings app may be killed at any time and only
 * talks to it through this service.
 */
public final class ControlService extends Service {
    private static final String TAG = "ftbridge";

    private final Messenger messenger =
            new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private boolean handleMessage(Message message) {
        switch (message.what) {
            case ControlProtocol.MSG_GET_STATE:
                break;
            case ControlProtocol.MSG_APPLY: {
                Settings store = new Settings(this);
                Settings.Values settings = store.load();
                settings.applyExtras(message.getData());
                store.store(settings);
                Log.i(TAG, "settings applied from the control interface");
                TrackingService.start(this);
                break;
            }
            case ControlProtocol.MSG_STOP:
                TrackingService.stop(this);
                break;
            default:
                return false;
        }

        reply(message.replyTo);
        return true;
    }

    private void reply(Messenger replyTo) {
        if (replyTo == null) {
            return;
        }

        Bundle state = new Settings(this).load().toBundle();
        state.putBoolean(ControlProtocol.KEY_SERVICE_RUNNING, TrackingService.isRunning());
        state.putString(ControlProtocol.KEY_BRIDGE_STATUS, NativeCore.status());
        state.putString(ControlProtocol.KEY_GATE_STATUS, TrackingService.gateStatus);
        state.putBoolean(ControlProtocol.KEY_USAGE_ACCESS, TrackingService.hasUsageAccess(this));
        state.putString(ControlProtocol.KEY_VERSION, versionName());

        Message message = Message.obtain(null, ControlProtocol.MSG_STATE);
        message.setData(state);
        try {
            replyTo.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "control client is gone: " + e);
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}

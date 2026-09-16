package dev.maverick.ftbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Settings settings = new Settings(context);
        if (settings.autostart() && !settings.host().isEmpty()) {
            TrackingService.start(context);
        }
    }
}

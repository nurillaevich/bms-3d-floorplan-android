package uz.bms.floorplan3d;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Relaunch the kiosk after the tablet reboots, so the panel is always up — you
 * never get dropped to the launcher after a power cycle.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            // Bring the remote-control endpoint back up too, so Home Assistant can
            // reach the panel after a power cycle without someone touching it.
            try {
                RemoteHttpService.sync(ctx);
            } catch (Exception ignored) {
            }
        }
    }
}

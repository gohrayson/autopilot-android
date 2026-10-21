package app.autopilot.android;

import android.content.*;

/** Only this app's explicit mutable PendingIntent is supplied to Android's package installer. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ((AutopilotApp)context.getApplicationContext()).updates.installResult(intent);
    }
}

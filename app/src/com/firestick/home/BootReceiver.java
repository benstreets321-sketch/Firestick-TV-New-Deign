package com.firestick.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the Fire OS 6 home keeper back after a restart or an update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Keeper.start(context);
    }
}

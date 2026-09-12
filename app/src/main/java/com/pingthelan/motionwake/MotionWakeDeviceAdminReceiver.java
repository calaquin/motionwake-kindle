package com.pingthelan.motionwake;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class MotionWakeDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "MotionWake";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "Real screen-off device administrator enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(TAG, "Real screen-off device administrator disabled");
    }
}

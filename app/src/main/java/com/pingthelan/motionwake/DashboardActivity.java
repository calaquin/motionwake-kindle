package com.pingthelan.motionwake;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

public class DashboardActivity extends Activity {

    private static final String TAG = "MotionWake";
    static final String ACTION_PREPARE_SCREEN_OFF =
            "com.pingthelan.motionwake.PREPARE_SCREEN_OFF";
    static final String EXTRA_MOTION_WAKE = "motion_wake";

    private DashboardWebView dashboardView;
    private boolean preparedForScreenOff;
    private boolean receiverRegistered;

    private final BroadcastReceiver displayReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null
                            && ACTION_PREPARE_SCREEN_OFF.equals(
                            intent.getAction())) {
                        prepareForScreenOff();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyBaseWindowFlags();
        resumeDashboardDisplay(
                getIntent() != null
                        && getIntent().getBooleanExtra(
                        EXTRA_MOTION_WAKE,
                        false
                )
        );

        dashboardView = new DashboardWebView(this);
        setContentView(dashboardView);

        registerReceiver(
                displayReceiver,
                new IntentFilter(ACTION_PREPARE_SCREEN_OFF)
        );
        receiverRegistered = true;

        dashboardView.hideSystemUi();
        dashboardView.start();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!preparedForScreenOff) {
            resumeDashboardDisplay(false);
        }

        if (dashboardView != null) {
            dashboardView.hideSystemUi();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent != null
                && intent.getBooleanExtra(EXTRA_MOTION_WAKE, false)) {
            resumeDashboardDisplay(true);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && dashboardView != null) {
            dashboardView.hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        if (dashboardView != null && dashboardView.canGoBack()) {
            dashboardView.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(displayReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }

        if (dashboardView != null) {
            dashboardView.shutdown();
            dashboardView = null;
        }

        super.onDestroy();
    }

    private void applyBaseWindowFlags() {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
    }

    private void prepareForScreenOff() {
        preparedForScreenOff = true;
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        Log.i(TAG, "Dashboard prepared for real screen-off");
    }

    private void resumeDashboardDisplay(boolean turnScreenOn) {
        preparedForScreenOff = false;

        int flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

        if (turnScreenOn) {
            flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        }

        getWindow().addFlags(flags);

        if (turnScreenOn) {
            Log.i(TAG, "Dashboard requested above-keyguard motion wake");
        }
    }
}

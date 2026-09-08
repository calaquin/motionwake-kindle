package com.pingthelan.motionwake;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class BlankActivity extends Activity {

    public static final String ACTION_REVEAL =
            "com.pingthelan.motionwake.action.REVEAL";

    private static final String TAG = "MotionWake";

    private BroadcastReceiver revealReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // Keep Android awake, but drive the LCD backlight to its
        // minimum possible level for this window.
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = 0.0f;
        params.buttonBrightness = 0.0f;
        getWindow().setAttributes(params);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        setContentView(root);

        hideSystemUi();

        revealReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REVEAL.equals(intent.getAction())) {
                    Log.i(TAG, "Reveal signal received; leaving blank mode");
                    revealDashboard();
                }
            }
        };

        registerReceiver(
                revealReceiver,
                new IntentFilter(ACTION_REVEAL)
        );

        Log.i(TAG,
                "Blank mode active; screen black at minimum brightness");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );
    }

    private void revealDashboard() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touching the fake-off display should reveal it too.
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "Blank screen touched; revealing dashboard");
            revealDashboard();
        }

        return true;
    }

    @Override
    protected void onDestroy() {
        if (revealReceiver != null) {
            try {
                unregisterReceiver(revealReceiver);
            } catch (Exception ignored) {
            }

            revealReceiver = null;
        }

        super.onDestroy();
    }
}

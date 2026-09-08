package com.pingthelan.motionwake;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class WakeActivity extends Activity {

    private static final String TAG = "MotionWake";

    // Long enough to make it obvious during testing whether the
    // keyguard actually disappears while this Activity is active.
    private static final long FINISH_DELAY_MS = 5000L;

    private final Handler handler = new Handler();

    private final Runnable finishRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "WakeActivity finishing; revealing previous app");
            finish();
            overridePendingTransition(0, 0);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);
        setContentView(root);

        Log.i(TAG,
                "Full-screen WakeActivity shown; requesting keyguard dismissal");

        handler.postDelayed(finishRunnable, FINISH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(finishRunnable);
        super.onDestroy();
    }
}

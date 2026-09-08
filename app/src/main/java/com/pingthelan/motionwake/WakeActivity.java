package com.pingthelan.motionwake;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;

public class WakeActivity extends Activity {

    private static final String TAG = "MotionWake";
    private static final long FINISH_DELAY_MS = 1000L;

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

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        Log.i(TAG, "WakeActivity shown; requesting non-secure keyguard dismissal");

        handler.postDelayed(finishRunnable, FINISH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(finishRunnable);
        super.onDestroy();
    }
}

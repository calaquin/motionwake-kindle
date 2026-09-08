package com.pingthelan.motionwake;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class BlankActivity extends Activity {

    private static final String TAG = "MotionWake";

    private static BlankActivity activeInstance;

    public static boolean finishActive() {
        final BlankActivity activity = activeInstance;

        if (activity == null) {
            return false;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing()) {
                    Log.i(TAG,
                            "BlankActivity finishing; revealing previous app");
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
            }
        });

        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        activeInstance = this;

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        WindowManager.LayoutParams params =
                getWindow().getAttributes();

        params.screenBrightness = 0.0f;
        params.buttonBrightness = 0.0f;

        getWindow().setAttributes(params);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        setContentView(root);

        hideSystemUi();

        Log.i(TAG,
                "Blank mode active; screen black at minimum brightness");
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
        hideSystemUi();
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            Log.i(TAG,
                    "Blank screen touched; revealing previous app");

            finish();
            overridePendingTransition(0, 0);
        }

        return true;
    }

    @Override
    protected void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }

        super.onDestroy();
    }
}

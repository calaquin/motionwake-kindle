package com.pingthelan.motionwake;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_ENABLE_DEVICE_ADMIN = 100;

    private EditText pixelDelta;
    private EditText motionPercent;
    private EditText sampleMs;
    private EditText consecutiveHits;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName deviceAdminComponent;
    private TextView screenOffStatus;
    private boolean startAfterAdminRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        devicePolicyManager = (DevicePolicyManager)
                getSystemService(Context.DEVICE_POLICY_SERVICE);
        deviceAdminComponent = new ComponentName(
                this,
                MotionWakeDeviceAdminReceiver.class
        );

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("MotionWake");
        title.setTextSize(28);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText(
            "Camera-only motion wake helper for the Kindle dashboard.\n\n" +
            "It stores no camera images and transmits no camera data.\n" +
            "The dashboard prefers PingTheLan and automatically falls back " +
            "to Pi-PingTheLan."
        );
        info.setTextSize(16);
        layout.addView(info);

        screenOffStatus = new TextView(this);
        screenOffStatus.setTextSize(16);
        layout.addView(screenOffStatus);

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);

        pixelDelta = addNumberField(layout, "Pixel change threshold (0-255)", 
                String.valueOf(p.getInt("pixelDelta", 24)));
        motionPercent = addNumberField(layout, "Changed pixels required (%)", 
                String.valueOf(p.getInt("motionPercent", 8)));
        sampleMs = addNumberField(layout, "Sampling interval (ms)", 
                String.valueOf(p.getInt("sampleMs", 500)));
        consecutiveHits = addNumberField(layout, "Consecutive motion samples required", 
                String.valueOf(p.getInt("consecutiveHits", 2)));

        Button saveStart = new Button(this);
        saveStart.setText("Save and Start MotionWake");
        saveStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                savePreferences();

                if (!isDeviceAdminActive()) {
                    requestDeviceAdminAndStart();
                    return;
                }

                startMotionWake();
            }
        });
        layout.addView(saveStart);

        Button stop = new Button(this);
        stop.setText("Stop MotionWake");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, MotionWakeService.class));
                Toast.makeText(MainActivity.this,
                        "MotionWake stopped.",
                        Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(stop);

        Button disableScreenOff = new Button(this);
        disableScreenOff.setText("Disable Real Screen-Off");
        disableScreenOff.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(
                        MainActivity.this,
                        MotionWakeService.class
                ));

                if (devicePolicyManager != null
                        && isDeviceAdminActive()) {
                    devicePolicyManager.removeActiveAdmin(
                            deviceAdminComponent
                    );
                }

                updateScreenOffStatus();
                Toast.makeText(
                        MainActivity.this,
                        "Real screen-off disabled and MotionWake stopped.",
                        Toast.LENGTH_LONG
                ).show();
            }
        });
        layout.addView(disableScreenOff);

        TextView hint = new TextView(this);
        hint.setText(
            "\nRecommended first test:\n" +
            "Pixel threshold: 24\n" +
            "Motion: 8%\n" +
            "Sample: 500 ms\n" +
            "Hits: 2\n\n" +
            "ADB logs:\n" +
            "adb logcat -s MotionWake"
        );
        hint.setTextSize(15);
        layout.addView(hint);

        setContentView(scroll);

        updateScreenOffStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScreenOffStatus();
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_ENABLE_DEVICE_ADMIN) {
            return;
        }

        updateScreenOffStatus();

        if (startAfterAdminRequest) {
            startAfterAdminRequest = false;
            startMotionWake();
        }
    }

    private boolean isDeviceAdminActive() {
        return devicePolicyManager != null
                && devicePolicyManager.isAdminActive(deviceAdminComponent);
    }

    private void updateScreenOffStatus() {
        if (screenOffStatus == null) {
            return;
        }

        if (isDeviceAdminActive()) {
            screenOffStatus.setText(
                    "\nReal screen-off: ENABLED\n"
                    + "The panel will power off after 30 seconds without motion."
            );
        } else {
            screenOffStatus.setText(
                    "\nReal screen-off: NOT ENABLED\n"
                    + "Starting MotionWake will request one-time permission. "
                    + "If declined, the dim black-overlay fallback is used."
            );
        }
    }

    private void requestDeviceAdminAndStart() {
        try {
            startAfterAdminRequest = true;

            Intent intent = new Intent(
                    DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
            );
            intent.putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    deviceAdminComponent
            );
            intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "MotionWake uses device administrator access only to "
                    + "turn the display fully off after inactivity."
            );

            startActivityForResult(
                    intent,
                    REQUEST_ENABLE_DEVICE_ADMIN
            );
        } catch (Throwable error) {
            startAfterAdminRequest = false;
            Toast.makeText(
                    this,
                    "Device administrator unavailable; using dim fallback.",
                    Toast.LENGTH_LONG
            ).show();
            startMotionWake();
        }
    }

    private void startMotionWake() {
        startService(new Intent(this, MotionWakeService.class));
        startActivity(new Intent(this, DashboardActivity.class));
    }

    private EditText addNumberField(LinearLayout layout, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText("\n" + label);
        tv.setTextSize(16);
        layout.addView(tv);

        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setText(value);
        layout.addView(edit);
        return edit;
    }

    private void savePreferences() {
        SharedPreferences.Editor e =
            PreferenceManager.getDefaultSharedPreferences(this).edit();

        e.putInt("pixelDelta", parse(pixelDelta, 24, 1, 255));
        e.putInt("motionPercent", parse(motionPercent, 8, 1, 100));
        e.putInt("sampleMs", parse(sampleMs, 500, 100, 10000));
        e.putInt("consecutiveHits", parse(consecutiveHits, 2, 1, 20));
        e.apply();
    }

    private int parse(EditText edit, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(edit.getText().toString().trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

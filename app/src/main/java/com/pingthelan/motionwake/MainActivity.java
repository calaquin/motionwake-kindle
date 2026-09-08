package com.pingthelan.motionwake;

import android.app.Activity;
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

    private EditText pixelDelta;
    private EditText motionPercent;
    private EditText sampleMs;
    private EditText consecutiveHits;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            "The built-in dashboard client connects only to the configured Pi dashboard."
        );
        info.setTextSize(16);
        layout.addView(info);

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
                startService(new Intent(MainActivity.this, MotionWakeService.class));
                startActivity(
                        new Intent(
                                MainActivity.this,
                                DashboardActivity.class
                        )
                );
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

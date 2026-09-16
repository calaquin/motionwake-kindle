package com.pingthelan.motionwake;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class TabletTelemetry {

    private static final String TAG = "MotionWake:Telemetry";
    private static final long REPORT_INTERVAL_MS = 25000L;
    private static final int HTTP_TIMEOUT_MS = 3000;

    private static final String[] DASHBOARD_TELEMETRY_URLS = new String[] {
            "http://10.0.0.133:8099/api/tablets/telemetry",
            "http://10.0.0.179:8099/api/tablets/telemetry"
    };

    private final Context appContext;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private volatile boolean running;

    private final Runnable reportRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                sendTelemetryReport();
            } catch (Throwable t) {
                Log.w(TAG, "Error generating telemetry report", t);
            }
            if (running && workerHandler != null) {
                workerHandler.postDelayed(this, REPORT_INTERVAL_MS);
            }
        }
    };

    public TabletTelemetry(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new HandlerThread("TabletTelemetryWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        workerHandler.post(reportRunnable);
        Log.i(TAG, "Tablet telemetry reporter started");
    }

    public synchronized void stop() {
        running = false;
        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
            workerHandler = null;
        }
        if (workerThread != null) {
            workerThread.quit();
            workerThread = null;
        }
        Log.i(TAG, "Tablet telemetry reporter stopped");
    }

    private void sendTelemetryReport() {
        final JSONObject payload = collectTelemetryJson();
        if (payload == null) {
            return;
        }

        final byte[] bodyBytes = payload.toString().getBytes();

        for (final String targetUrl : DASHBOARD_TELEMETRY_URLS) {
            postTelemetryToUrl(targetUrl, bodyBytes);
        }
    }

    private void postTelemetryToUrl(String endpointUrl, byte[] jsonBytes) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));

            OutputStream os = conn.getOutputStream();
            os.write(jsonBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.d(TAG, "Telemetry delivered to " + endpointUrl + " (HTTP " + code + ")");
            } else {
                Log.d(TAG, "Telemetry delivery HTTP " + code + " from " + endpointUrl);
            }
        } catch (Throwable t) {
            Log.d(TAG, "Telemetry delivery failed for " + endpointUrl + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private JSONObject collectTelemetryJson() {
        try {
            JSONObject root = new JSONObject();

            String deviceId = "kindle-fire-hd";
            if (Build.SERIAL != null && !Build.SERIAL.equalsIgnoreCase("unknown")) {
                deviceId = "kindle-" + Build.SERIAL.toLowerCase();
            }

            root.put("device_id", deviceId);
            root.put("name", "Kindle Fire HD (" + Build.MODEL + ")");
            root.put("model", Build.MODEL != null ? Build.MODEL : "Kindle Fire HD");

            // 1. Battery metrics
            JSONObject batteryObj = new JSONObject();
            Intent batteryIntent = appContext.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                int tempRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

                int percent = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
                boolean isCharging = (plugged == BatteryManager.BATTERY_PLUGGED_AC
                        || plugged == BatteryManager.BATTERY_PLUGGED_USB);
                String pluggedStr = (plugged == BatteryManager.BATTERY_PLUGGED_AC) ? "ac"
                        : ((plugged == BatteryManager.BATTERY_PLUGGED_USB) ? "usb" : "none");

                batteryObj.put("level", percent);
                batteryObj.put("charging", isCharging);
                batteryObj.put("plugged", pluggedStr);
                batteryObj.put("health", health == BatteryManager.BATTERY_HEALTH_GOOD ? "good" : "warning");
                if (tempRaw > 0) {
                    batteryObj.put("temperature_c", tempRaw / 10.0);
                }
            }
            root.put("battery", batteryObj);

            // 2. WiFi metrics
            JSONObject wifiObj = new JSONObject();
            try {
                WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null) {
                        int rssi = wifiInfo.getRssi();
                        String ssid = wifiInfo.getSSID();
                        if (ssid != null) {
                            ssid = ssid.replace("\"", "");
                        }
                        int linkSpeed = wifiInfo.getLinkSpeed();
                        wifiObj.put("rssi", rssi);
                        wifiObj.put("ssid", ssid != null ? ssid : "WiFi");
                        wifiObj.put("link_speed_mbps", linkSpeed);
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "Unable to query WiFi metrics", t);
            }
            root.put("wifi", wifiObj);

            // 3. Display metrics
            JSONObject displayObj = new JSONObject();
            PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = powerManager != null && powerManager.isScreenOn();
            displayObj.put("screen_on", screenOn);
            root.put("display", displayObj);

            // 4. System metrics
            JSONObject sysObj = new JSONObject();
            sysObj.put("uptime_seconds", SystemClock.elapsedRealtime() / 1000L);
            sysObj.put("os_version", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            sysObj.put("app_version", "MotionWake 0.2.0");
            sysObj.put("serial", Build.SERIAL);
            root.put("system", sysObj);

            return root;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to collect tablet telemetry", t);
            return null;
        }
    }
}


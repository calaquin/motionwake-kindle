package com.pingthelan.motionwake;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.List;

@SuppressWarnings("deprecation")
public class MotionWakeService extends Service implements Camera.PreviewCallback {

    private static final String TAG = "MotionWake";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKE_PULSE_MS = 3000L;
    private static final long BLANK_DELAY_MS = 30000L;
    private static final long BLANK_SETTLE_MS = 1500L;

    private Camera camera;
    private SurfaceTexture dummyTexture;

    private PowerManager powerManager;
    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    private final Handler handler = new Handler();

    private int previewWidth;
    private int previewHeight;

    private int pixelDelta = 24;
    private int motionPercent = 8;
    private int sampleMs = 500;
    private int consecutiveHitsRequired = 2;

    private long lastProcessedMs = 0;
    private long warmupUntilMs = 0;
    private int consecutiveHits = 0;

    private byte[] previousY;

    private final Runnable releaseScreenRunnable = new Runnable() {
        @Override
        public void run() {
            releaseScreenWakeLock();
        }
    };

    private final Runnable blankRunnable = new Runnable() {
        @Override
        public void run() {
            enterBlankMode();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        loadPreferences();
        startForegroundCompat();
        acquireCpuWakeLock();
        startCamera();
        scheduleBlank();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadPreferences();

        if (camera == null) {
            startCamera();
        }

        scheduleBlank();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(releaseScreenRunnable);
        handler.removeCallbacks(blankRunnable);
        sendRevealSignal();
        stopCamera();
        releaseScreenWakeLock();

        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
        cpuWakeLock = null;

        stopForeground(true);

        Log.i(TAG, "Service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loadPreferences() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        pixelDelta = p.getInt("pixelDelta", 24);
        motionPercent = p.getInt("motionPercent", 8);
        sampleMs = p.getInt("sampleMs", 500);
        consecutiveHitsRequired = p.getInt("consecutiveHits", 2);

        Log.i(TAG, "Settings: pixelDelta=" + pixelDelta
                + " motionPercent=" + motionPercent
                + " sampleMs=" + sampleMs
                + " consecutiveHits=" + consecutiveHitsRequired
                + " wakePulseMs=" + WAKE_PULSE_MS);
    }

    private void startForegroundCompat() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this)
                .setContentTitle("MotionWake")
                .setContentText("Watching front camera for motion")
                .setSmallIcon(com.pingthelan.motionwake.R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .getNotification();

        startForeground(NOTIFICATION_ID, notification);
    }

    private void acquireCpuWakeLock() {
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);

        cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MotionWake:CameraCPU");
        cpuWakeLock.setReferenceCounted(false);
        cpuWakeLock.acquire();

        // Do not hold this continuously. ACQUIRE_CAUSES_WAKEUP works when
        // the lock transitions from unheld to held, so this is used only
        // as a short pulse when motion occurs while the display is off.
        screenWakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                "MotionWake:Screen");
        screenWakeLock.setReferenceCounted(false);
    }

    private void startCamera() {
        try {
            int cameraId = findFrontCamera();
            if (cameraId < 0) {
                Log.e(TAG, "No front-facing camera found");
                stopSelf();
                return;
            }

            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();

            Camera.Size selected = chooseSmallestPreview(params.getSupportedPreviewSizes());
            if (selected != null) {
                params.setPreviewSize(selected.width, selected.height);
            }

            if (supportsPreviewFormat(params.getSupportedPreviewFormats(), ImageFormat.NV21)) {
                params.setPreviewFormat(ImageFormat.NV21);
            }

            camera.setParameters(params);

            Camera.Size actual = camera.getParameters().getPreviewSize();
            previewWidth = actual.width;
            previewHeight = actual.height;

            Log.i(TAG, "Using camera " + cameraId
                    + " preview " + previewWidth + "x" + previewHeight
                    + " format=" + camera.getParameters().getPreviewFormat());

            List<Camera.Size> sizes = camera.getParameters().getSupportedPreviewSizes();
            if (sizes != null) {
                StringBuilder sb = new StringBuilder("Supported preview sizes:");
                for (Camera.Size s : sizes) {
                    sb.append(" ").append(s.width).append("x").append(s.height);
                }
                Log.i(TAG, sb.toString());
            }

            dummyTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(dummyTexture);
            camera.setPreviewCallback(this);

            previousY = null;
            consecutiveHits = 0;
            lastProcessedMs = 0;
            warmupUntilMs = SystemClock.elapsedRealtime() + 3000;

            camera.startPreview();
            Log.i(TAG, "Camera preview started; 3 second warmup");

        } catch (Throwable t) {
            Log.e(TAG, "Unable to start camera", t);
            stopCamera();
            stopSelf();
        }
    }

    private int findFrontCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();

        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            Log.i(TAG, "Camera " + i + " facing=" + info.facing
                    + " orientation=" + info.orientation);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i;
            }
        }

        // Some old single-camera devices report unusual metadata.
        return count > 0 ? 0 : -1;
    }

    private Camera.Size chooseSmallestPreview(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Camera.Size best = sizes.get(0);

        for (Camera.Size size : sizes) {
            long pixels = (long) size.width * (long) size.height;
            long bestPixels = (long) best.width * (long) best.height;

            if (pixels < bestPixels) {
                best = size;
            }
        }

        return best;
    }

    private boolean supportsPreviewFormat(List<Integer> formats, int desired) {
        if (formats == null) return false;
        for (Integer format : formats) {
            if (format != null && format == desired) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        if (data == null || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now < warmupUntilMs) {
            if (now - lastProcessedMs >= sampleMs) {
                saveCurrentY(data);
                lastProcessedMs = now;
            }
            return;
        }

        if (now - lastProcessedMs < sampleMs) {
            return;
        }
        lastProcessedMs = now;

        int yLength = previewWidth * previewHeight;
        if (data.length < yLength) {
            Log.w(TAG, "Preview buffer smaller than expected Y plane");
            return;
        }

        if (previousY == null || previousY.length != yLength) {
            saveCurrentY(data);
            return;
        }

        // Subsample the luminance plane. This keeps work extremely small
        // on the Kindle while still providing enough points for motion.
        final int step = 8;
        long sumCurrent = 0;
        long sumPrevious = 0;
        int samples = 0;

        for (int i = 0; i < yLength; i += step) {
            sumCurrent += data[i] & 0xff;
            sumPrevious += previousY[i] & 0xff;
            samples++;
        }

        int globalShift = 0;
        if (samples > 0) {
            globalShift = (int)((sumCurrent - sumPrevious) / samples);
        }

        int changed = 0;

        for (int i = 0; i < yLength; i += step) {
            int current = data[i] & 0xff;
            int previous = previousY[i] & 0xff;
            int diff = Math.abs((current - previous) - globalShift);

            if (diff >= pixelDelta) {
                changed++;
            }
        }

        int percent = samples == 0 ? 0 : (changed * 100 / samples);

        System.arraycopy(data, 0, previousY, 0, yLength);

        if (percent >= motionPercent) {
            consecutiveHits++;
            Log.i(TAG, "Motion sample: " + percent + "% changed (hit "
                    + consecutiveHits + "/" + consecutiveHitsRequired + ")");

            if (consecutiveHits >= consecutiveHitsRequired) {
                onMotionDetected(percent);
                consecutiveHits = 0;
            }
        } else {
            if (percent > 0) {
                Log.d(TAG, "Quiet sample: " + percent + "% changed");
            }
            consecutiveHits = 0;
        }
    }

    private void saveCurrentY(byte[] data) {
        int yLength = previewWidth * previewHeight;
        if (data.length < yLength) return;

        if (previousY == null || previousY.length != yLength) {
            previousY = new byte[yLength];
        }
        System.arraycopy(data, 0, previousY, 0, yLength);
    }

    private void onMotionDetected(int score) {
        boolean screenOn =
                powerManager != null && powerManager.isScreenOn();

        Log.i(TAG, "MOTION DETECTED: " + score
                + "% screenOn=" + screenOn);

        // A real motion event always resets our own fake-off timer.
        handler.removeCallbacks(blankRunnable);

        // If BlankActivity is active, this closes it immediately and
        // exposes the task that was underneath it.
        sendRevealSignal();

        // Revealing the dashboard changes the light coming from the screen.
        // Give the camera a moment to establish a fresh baseline.
        settleMotionDetector(1000L);

        // Genuine screen-off is now only a fallback case. Normal operation
        // should stay awake inside BlankActivity and never reach keyguard.
        if (!screenOn) {
            wakeDisplay();
        }

        scheduleBlank();
    }

    private void scheduleBlank() {
        handler.removeCallbacks(blankRunnable);
        handler.postDelayed(blankRunnable, BLANK_DELAY_MS);
    }

    private void enterBlankMode() {
        boolean screenOn =
                powerManager != null && powerManager.isScreenOn();

        if (!screenOn) {
            Log.i(TAG,
                    "Display already genuinely off; skipping fake-off mode");
            return;
        }

        try {
            // Turning the LCD from a bright dashboard to black can itself
            // alter the camera image. Reset the detector and let it settle
            // so blank mode does not immediately wake itself back up.
            settleMotionDetector(BLANK_SETTLE_MS);

            Intent blankIntent =
                    new Intent(this, BlankActivity.class);

            blankIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
            );

            startActivity(blankIntent);

            Log.i(TAG,
                    "No motion for 30 seconds; BlankActivity launched");

        } catch (Throwable t) {
            Log.e(TAG, "Unable to enter blank mode", t);
        }
    }

    private void sendRevealSignal() {
        try {
            Intent reveal =
                    new Intent(BlankActivity.ACTION_REVEAL);

            reveal.setPackage(getPackageName());
            sendBroadcast(reveal);

        } catch (Throwable t) {
            Log.e(TAG, "Unable to send reveal signal", t);
        }
    }

    private void settleMotionDetector(long durationMs) {
        previousY = null;
        consecutiveHits = 0;
        warmupUntilMs =
                SystemClock.elapsedRealtime() + durationMs;
    }

    private void wakeDisplay() {
        try {
            handler.removeCallbacks(releaseScreenRunnable);

            // ACQUIRE_CAUSES_WAKEUP requires a fresh acquisition.
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
            }

            if (screenWakeLock != null) {
                screenWakeLock.acquire();

                Log.i(TAG, "Wake pulse acquired for "
                        + WAKE_PULSE_MS + " ms");

                handler.postDelayed(
                        releaseScreenRunnable,
                        WAKE_PULSE_MS
                );
            }

        } catch (Throwable t) {
            Log.e(TAG, "Unable to complete wake sequence", t);
        }
    }

    private void releaseScreenWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock.isHeld()) {
                screenWakeLock.release();
                Log.i(TAG, "Wake pulse released");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to release screen wake lock", t);
        }
    }

    private void stopCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
            } catch (Exception ignored) {
            }

            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }

            try {
                camera.release();
            } catch (Exception ignored) {
            }

            camera = null;
        }

        if (dummyTexture != null) {
            try {
                dummyTexture.release();
            } catch (Exception ignored) {
            }
            dummyTexture = null;
        }

        previousY = null;
    }
}

package com.pingthelan.motionwake;

import android.app.KeyguardManager;
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

    private Camera camera;
    private SurfaceTexture dummyTexture;

    private PowerManager powerManager;
    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private KeyguardManager.KeyguardLock keyguardLock;

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

    @Override
    public void onCreate() {
        super.onCreate();

        loadPreferences();
        startForegroundCompat();
        acquireCpuWakeLock();
        disableNonSecureKeyguard();
        startCamera();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadPreferences();

        if (camera == null) {
            startCamera();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(releaseScreenRunnable);
        stopCamera();
        releaseScreenWakeLock();

        if (keyguardLock != null) {
            try {
                keyguardLock.reenableKeyguard();
            } catch (Exception ignored) {
            }
            keyguardLock = null;
        }

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

    private void disableNonSecureKeyguard() {
        try {
            KeyguardManager km =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            keyguardLock = km.newKeyguardLock("MotionWake:Keyguard");
            keyguardLock.disableKeyguard();
            Log.i(TAG, "Requested non-secure keyguard disable");
        } catch (Exception e) {
            Log.e(TAG, "Could not disable keyguard", e);
        }
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

        // Motion while the dashboard is already visible should not hold
        // a screen wake lock. Fire OS remains responsible for timeout.
        if (!screenOn) {
            wakeDisplay();
        }
    }

    private void wakeDisplay() {

        // Amazon's Fire OS build may reject disableKeyguard() even though
        // DISABLE_KEYGUARD is declared. Keyguard handling is best-effort;
        // failure here must never prevent the actual display wake.
        if (keyguardLock != null) {
            try {
                keyguardLock.disableKeyguard();
                Log.i(TAG, "Non-secure keyguard disable requested");
            } catch (Throwable t) {
                Log.w(TAG,
                        "Keyguard disable unavailable; continuing with wake pulse",
                        t);
            }
        }

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
            Log.e(TAG, "Unable to acquire wake pulse", t);
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

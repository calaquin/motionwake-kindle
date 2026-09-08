# MotionWake for Kindle Fire HD (Android 4.0.3 / API 15)

MotionWake is a tiny native Android helper intended to keep a browser-based
server dashboard on an old Kindle Fire and wake the display when the
front-facing camera detects motion.

## Design

- Native Android Camera API.
- No AndroidX.
- No Google Play Services.
- No network permission.
- No image/video storage.
- Front camera frames remain on-device.
- Targets Android API 15.
- Keeps a partial CPU wake lock while monitoring.
- Uses a screen wake lock only after motion.
- Attempts to disable the non-secure keyguard while the service is alive.
- Starts automatically after boot.
- Foreground service notification reduces the chance Fire OS kills it.

## Default motion settings

- Pixel-change threshold: 24 / 255
- Changed-pixel threshold: 8%
- Sample interval: 500 ms
- Screen hold after motion: 60 seconds
- Consecutive motion samples: 2
- Initial camera warmup: 3 seconds

All five tuning values can be changed in the app.

## Build

GitHub Actions builds the debug APK automatically on every push to `main`
and can also be run manually.

The APK appears in the workflow run as the artifact:

`motionwake-debug-apk`

## Install

With ADB connected to the Kindle:

```bash
adb install -r app-debug.apk
```

Launch the configuration screen:

```bash
adb shell am start -n com.pingthelan.motionwake/.MainActivity
```

Tap **Save and Start MotionWake**, then return to Silk.

## Logs

```bash
adb logcat -c
adb logcat -s MotionWake
```

Expected startup messages include supported camera preview sizes and the
selected preview size.

## Stop for testing

Use the app's **Stop MotionWake** button, or:

```bash
adb shell am force-stop com.pingthelan.motionwake
```

After a force-stop, Android normally will not deliver BOOT_COMPLETED to the
app until it has been launched again manually.

## First motion test

1. Start MotionWake.
2. Open the Silk dashboard.
3. Leave the Kindle alone for at least 60 seconds so Fire OS turns the display off.
4. Move in front of the front camera.
5. Watch `adb logcat -s MotionWake`.
6. Confirm the display wakes and Silk is visible without a swipe.

If the screen stays off but logs report motion, the camera detector is working
and only the Fire-specific wake behavior needs adjustment.

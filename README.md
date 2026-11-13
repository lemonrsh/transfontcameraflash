# Front Flash Tester

A tiny Android app for testing whether Android exposes a **front-facing camera flash as a controllable torch**. It discovers front camera IDs at runtime and uses `CameraManager.setTorchMode()`—it does not assume the front camera is always ID `1`.

## What it does

- Lists front-facing cameras whose Camera2 characteristics report `FLASH_INFO_AVAILABLE = true`.
- Sends explicit **ON** and **OFF** torch requests and displays Android's reported torch state or error.
- Requests the torch be turned off when the app leaves the foreground (best effort).
- Uses no root, does not open a camera preview, does not capture or store media, and has no network permission.

## Important limitation

A front camera can advertise a flash unit while its selfie light is only available through a vendor camera app's **screen-flash/capture flow**. `setTorchMode()` can control only a torch that the camera HAL exposes to Android. This app cannot force a vendor-only screen flash or repair a GSI camera HAL. If the request is rejected or never confirmed, try closing GCam and other camera apps, rescan, and test the stock camera app's selfie flash separately.

## Install and test

1. Install the debug APK on the phone and open **Front Flash Tester**.
2. Tap **Grant camera access**. Android requires this permission for camera metadata and torch control; the app does not take pictures.
3. Close GCam and any other app currently using a camera, then tap **Scan front cameras** / **Rescan**.
4. Select the front camera that reports flash support, tap **ON**, and then tap **OFF**. The status is taken from Android's torch callback; an error means the HAL did not accept or confirm the request.

To install from a computer with ADB:

```sh
adb install -r app-debug.apk
```

The app is a diagnostic test of continuous torch mode, not a guarantee that the selfie flash will fire while taking a photo.

## Build

Requires JDK 17 and Android SDK Platform 35. From the repository root:

```sh
./gradlew assembleDebug
```

The installable, debug-signed APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the same APK on pushes, pull requests, and manual workflow dispatch. Download the `front-flash-tester-debug-apk` artifact from the completed workflow run. Debug APKs are for sideloading/testing, not Play Store publishing.

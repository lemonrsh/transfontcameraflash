# Front Flash tools

This repository contains two ways to test/control a **front-facing camera flash as a continuous torch**:

1. **Front Flash Tester** (`app`) is a normal diagnostic APK. It shows the detected front cameras, Android torch callbacks, and errors.
2. **Front Flash Quick Settings** (`tile` + `magisk`) is a Magisk module. After installation and reboot it installs a hidden, non-launchable helper package and automatically adds a **Front flash** Quick Settings tile.

Neither option can create a physical front flash or force a vendor-only selfie **screen flash**. They can only use a torch that the installed camera HAL exposes through Android's `CameraManager.setTorchMode()` API.

## Magisk module: Front Flash Quick Settings

The module is for a rooted phone running Android 7.0 or newer with Magisk. It does not require a separate APK install, does not have a launcher app, and does not show a runtime Camera-permission dialog.

At boot, its root service:

- mounts the included helper APK systemlessly under `/system/priv-app`;
- grants that helper `android.permission.CAMERA` using `pm grant` before it is used; and
- calls Android's `cmd statusbar add-tile` to add the **Front flash** tile to the owner user's Quick Settings panel.

The tile discovers a front-facing camera with `FLASH_INFO_AVAILABLE = true`, requests torch ON/OFF, and reflects Android's torch callbacks. It does not open a preview, save media, or use the network.

### Install

1. Download the GitHub Actions artifact named **`front-flash-quick-settings-magisk-module`**.
2. Extract that download once. GitHub wraps every Actions artifact in an outer ZIP.
3. In the Magisk app, select **Modules** → **Install from storage** and choose the extracted inner file: `FrontFlashQuickSettings-v1.0.0.zip`.
4. Reboot the phone.
5. Pull down Quick Settings. **Front flash** should already be present. If SystemUI has not finished starting, wait about a minute and open Quick Settings again.

Do **not** select the ZIP downloaded directly from the Actions artifact page: that outer ZIP does not have `module.prop` at its root, so Magisk correctly reports it as "not a Magisk module."

Use the Magisk app to disable or uninstall the module, then reboot. The module's uninstall hook removes its tile reference. Boot-service output is written to:

```text
/data/adb/frontflash-quicksettings.log
```

### Important limitations

- The module grants camera access automatically **because Magisk runs the boot script as root**. Do not install it unless you trust the ZIP.
- It cannot bypass a camera HAL that refuses a front torch request. A stock camera app may still support a selfie screen-flash capture flow that no Quick Settings tile can invoke.
- If another camera app owns the front camera, the tile becomes unavailable until that app releases it.
- The automatic tile command targets Android's owner user. Heavily modified OEM SystemUI builds can reject custom-tile registration; inspect the log above if the tile is missing.
- This is for Magisk installation, not stock-recovery flashing.

## Diagnostic APK: Front Flash Tester

The tester discovers front camera IDs at runtime; it does not assume the front camera is always ID `1`.

### What it does

- Lists front-facing cameras whose Camera2 characteristics report `FLASH_INFO_AVAILABLE = true`.
- Sends explicit **ON** and **OFF** torch requests and displays Android's reported torch state or error.
- Requests the torch be turned off when the app leaves the foreground (best effort).
- Uses no root, does not open a camera preview, does not capture or store media, and has no network permission.

### Install and test

1. Install the debug APK and open **Front Flash Tester**.
2. Tap **Grant camera access**. Android requires this permission for camera metadata and torch control; the app does not take pictures.
3. Close GCam and any other app currently using a camera, then tap **Scan front cameras** / **Rescan**.
4. Select the front camera that reports flash support, tap **ON**, and then tap **OFF**. The status is taken from Android's torch callback; an error means the HAL did not accept or confirm the request.

```sh
adb install -r app-debug.apk
```

## Build

Building requires JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and `zip`.

```sh
./gradlew :app:assembleDebug :tile:assembleDebug
bash scripts/build-magisk-module.sh
```

Outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
tile/build/outputs/apk/debug/tile-debug.apk
build/magisk/FrontFlashQuickSettings-v1.0.0.zip
```

GitHub Actions builds all three on pushes, pull requests, and manual workflow dispatch. The **`front-flash-quick-settings-magisk-module`** artifact is retained for 14 days, but must be extracted once before selecting its inner module ZIP in Magisk. The **`front-flash-tester-debug-apk`** artifact contains the diagnostic APK.

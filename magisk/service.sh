#!/system/bin/sh
# Runs as root through Magisk late_start service after every completed boot.
# It grants the hidden tile helper its requested runtime permission and asks SystemUI to add it.

MODDIR=${0%/*}
PACKAGE=io.github.lemonrsh.frontflashqs
COMPONENT="${PACKAGE}/.FrontFlashTileService"
LOG_FILE=/data/adb/frontflash-quicksettings.log

mkdir -p /data/adb 2>/dev/null
exec >>"$LOG_FILE" 2>&1

echo "$(date '+%Y-%m-%d %H:%M:%S') Front Flash Quick Settings: service started"

# CameraService and SystemUI are not dependable until Android has completed the boot phase.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Wait for Package Manager to scan the APK that Magisk mounts in /system/priv-app.
attempt=0
while [ "$attempt" -lt 60 ]; do
    if pm path "$PACKAGE" >/dev/null 2>&1; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done

if ! pm path "$PACKAGE" >/dev/null 2>&1; then
    echo "Hidden helper package was not scanned; will retry after the next reboot."
    exit 0
fi

# CAMERA is a dangerous runtime permission. Grant it through root so Android never prompts the
# user. Repeating this is harmless and restores the grant if a ROM revokes it during an update.
if pm grant "$PACKAGE" android.permission.CAMERA; then
    echo "CAMERA permission granted to $PACKAGE"
else
    echo "Unable to grant CAMERA permission to $PACKAGE"
fi

# This is the documented StatusBar shell command. Magisk runs this script as root, so it can add
# a custom tile without asking the user to edit the Quick Settings panel manually.
attempt=0
while [ "$attempt" -lt 20 ]; do
    if cmd statusbar add-tile "$COMPONENT"; then
        echo "Quick Settings tile added: $COMPONENT"
        exit 0
    fi
    attempt=$((attempt + 1))
    sleep 3
done

# Some heavily customized SystemUI builds omit the statusbar command but still watch their normal
# tile setting. Do not overwrite an unset setting because that would discard the ROM's defaults.
TILES="$(settings get secure sysui_qs_tiles 2>/dev/null)"
TILE_SPEC="custom($COMPONENT)"
case ",$TILES," in
    *",$TILE_SPEC,"*)
        echo "Quick Settings tile was already present: $COMPONENT"
        exit 0
        ;;
esac
if [ -n "$TILES" ] && [ "$TILES" != "null" ] \
        && settings put secure sysui_qs_tiles "$TILES,$TILE_SPEC"; then
    echo "Quick Settings tile added through sysui_qs_tiles fallback: $COMPONENT"
    exit 0
fi

echo "Could not add the Quick Settings tile automatically. Check $LOG_FILE."
exit 0

#!/system/bin/sh
# Remove the SystemUI tile reference while the helper APK is still mounted.
PACKAGE=io.github.lemonrsh.frontflashqs
COMPONENT="${PACKAGE}/.FrontFlashTileService"

cmd statusbar remove-tile "$COMPONENT" >/dev/null 2>&1 || true
rm -f /data/adb/frontflash-quicksettings.log

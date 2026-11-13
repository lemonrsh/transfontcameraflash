# Magisk module installer configuration. SKIPUNZIP=0 lets Magisk extract the system overlay,
# boot service, and hidden helper APK using its standard installer flow.
SKIPUNZIP=0

# New Magisk installers apply 0644 to ordinary files after extraction. The boot hook must be
# executable for magiskd to run it during late_start service.
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

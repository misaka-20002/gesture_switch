# Sourced by the module installer (KernelSU / Magisk) during installation.

# Zygisk API v5 (module/jni/zygisk.hpp). On Magisk that needs v27.0+; KernelSU and APatch
# report a fixed MAGISK_VER_CODE and rely on a Zygisk provider such as NeoZygisk.
if [ -z "$KSU" ] && [ -z "$APATCH" ] && [ "$MAGISK_VER_CODE" -lt 27000 ]; then
  abort "! Magisk v27.0+ is required (Zygisk API v5)"
fi

# DeviceStateManager exists from Android 12L; tested design targets Android 13-16.
if [ "$API" -lt 33 ]; then
  ui_print "! Android API $API is older than 13: fold detection may not work"
fi

if [ "$ARCH" != "arm64" ]; then
  ui_print "! Untested architecture: $ARCH"
fi

# Keep the user's settings when updating from the manager. (When flashed from recovery,
# MODPATH is the installed directory itself and config.prop has already been replaced.)
OLD_CONFIG=/data/adb/modules/$MODID/config.prop
if [ -f "$OLD_CONFIG" ] && [ "$OLD_CONFIG" != "$MODPATH/config.prop" ]; then
  ui_print "- Keeping existing config.prop"
  cp -f "$OLD_CONFIG" "$MODPATH/config.prop"
fi

ui_print "- Requires a Zygisk provider (NeoZygisk on KernelSU, or Magisk Zygisk)"
ui_print "- Reboot to activate. Logs: logcat -s GestureSwitch"

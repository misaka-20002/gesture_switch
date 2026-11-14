# Sourced by the module installer (Magisk's install_module) during installation.
# Zygisk API v5 (module/jni/zygisk.hpp) requires Magisk 27000+.
# KernelSU / APatch report a fixed MAGISK_VER_CODE, so only check real Magisk.
if [ -z "$KSU" ] && [ -z "$APATCH" ] && [ "$MAGISK_VER_CODE" -lt 27000 ]; then
  abort "! Magisk v27.0+ is required (Zygisk API v5)"
fi

# Runs early at boot when the module is being removed, before system_server starts.
# Restore the "open" navigation mode once the system is up, so the phone is not left in
# the folded mode (e.g. 3-button) after uninstalling.

MODDIR=${0%/*}
MODE=$(sh "$MODDIR/settings.sh" open-mode) || MODE=gestural

(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
  done
  sleep 5
  cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$MODE"
) </dev/null >/dev/null 2>&1 &

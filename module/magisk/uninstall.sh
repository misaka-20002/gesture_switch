# Runs early at boot when the module is being removed, before system_server starts.
# Restore the "open" navigation mode once the system is up, so the phone is not left in
# the folded mode (e.g. 3-button) after uninstalling.

MODDIR=${0%/*}
MODE=$(LC_ALL=C awk '
  function trim(s) {
    sub(/^[[:space:]]+/, "", s)
    sub(/[[:space:]]+$/, "", s)
    return s
  }
  {
    eq = index($0, "=")
    if (eq == 0 || trim(substr($0, 1, eq - 1)) != "open_mode") next
    value = trim(substr($0, eq + 1))
    if (value ~ /^(gestural|threebutton|twobutton)$/) mode = value
  }
  END { if (mode != "") print mode }
' "$MODDIR/config.prop" 2>/dev/null)
case "$MODE" in
  gestural|threebutton|twobutton) ;;
  *) MODE=gestural ;;
esac

(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
  done
  sleep 5
  cmd overlay enable-exclusive --category "com.android.internal.systemui.navbar.$MODE"
) </dev/null >/dev/null 2>&1 &

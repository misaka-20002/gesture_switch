#!/system/bin/sh

MODDIR=${0%/*}
CONFIG="$MODDIR/config.prop"
PREFIX=com.android.internal.systemui.navbar
MODES="gestural threebutton twobutton"

trim() {
  trimmed=${1#"${1%%[![:space:]]*}"}
  trimmed=${trimmed%"${trimmed##*[![:space:]]}"}
}

valid_mode() {
  case "$1" in
    gestural|threebutton|twobutton) return 0 ;;
    *) return 1 ;;
  esac
}

parse_debounce() {
  digits=${1#[+-]}
  case "$digits" in
    ''|*[!0-9]*) return 1 ;;
  esac
  # Remove leading zeroes before comparing or passing the value to test.
  digits=${digits#"${digits%%[!0]*}"}
  digits=${digits:-0}
  # Match the range accepted by Java Long.parseLong before applying its clamp.
  case "$1" in
    -*) limit=9223372036854775808 ;;
    *) limit=9223372036854775807 ;;
  esac
  if [ "${#digits}" -gt 19 ] ||
     { [ "${#digits}" -eq 19 ] && [ "$digits" \> "$limit" ]; }; then
    return 1
  fi
  case "$1" in
    -*) delay=0 ;;
    *)
      if [ "${#digits}" -gt 4 ] || [ "$digits" -gt 5000 ]; then
        delay=5000
      else
        delay=$digits
      fi
      ;;
  esac
}

read_config() {
  folded=threebutton
  opened=gestural
  debounce=400
  [ -f "$CONFIG" ] || return 0

  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      *=*) ;;
      *) continue ;;
    esac
    trim "${line#*=}"
    value=$trimmed
    trim "${line%%=*}"
    case "$trimmed" in
      folded_mode) if valid_mode "$value"; then folded=$value; fi ;;
      open_mode) if valid_mode "$value"; then opened=$value; fi ;;
      debounce_ms) if parse_debounce "$value"; then debounce=$delay; fi ;;
    esac
  done < "$CONFIG"
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

query_modes() {
  user=$(am get-current-user) || fail 'Cannot identify the current Android user.'
  case "$user" in
    ''|*[!0-9]*) fail 'Cannot identify the current Android user.' ;;
  esac
  cmd overlay list --user "$user" android >/dev/null || fail 'Cannot query Android navigation overlays.'

  available=''
  for mode in $MODES; do
    package="$PREFIX.$mode"
    state=$(dumpsys overlay --user "$user" state "$package") ||
      fail 'Cannot read navigation overlay state.'
    case "$state" in
      STATE_ENABLED|STATE_DISABLED) ;;
      *) continue ;;
    esac
    mutable=$(dumpsys overlay --user "$user" ismutable "$package") ||
      fail 'Cannot read navigation overlay permissions.'
    if [ "$mutable" = true ]; then
      available="${available:+$available }$mode"
    fi
  done
}

write_config() {
  if [ -f "$CONFIG" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        *=*)
          trim "${line%%=*}"
          case "$trimmed" in
            folded_mode|open_mode) continue ;;
            debounce_ms) if [ -n "$3" ]; then continue; fi ;;
          esac
          ;;
      esac
      printf '%s\n' "$line" || return 1
    done < "$CONFIG" || return 1
  fi
  printf 'folded_mode=%s\nopen_mode=%s\n' "$1" "$2" || return 1
  if [ -n "$3" ]; then
    printf 'debounce_ms=%s\n' "$3" || return 1
  fi
}

save_config() {
  for requested_mode in "$1" "$2"; do
    valid_mode "$requested_mode" || fail 'Unknown navigation mode.'
  done
  query_modes
  for requested_mode in "$1" "$2"; do
    case " $available " in
      *" $requested_mode "*) ;;
      *) fail "Navigation mode unavailable for user $user: $requested_mode" ;;
    esac
  done

  temp=$(mktemp "$MODDIR/.config.XXXXXX") || fail 'Cannot create a temporary configuration file.'
  trap 'rm -f "$temp"' EXIT
  trap 'exit 1' HUP INT TERM
  write_config "$1" "$2" "$3" > "$temp" || fail 'Cannot write configuration.'
  chmod 0644 "$temp" || fail 'Cannot set configuration permissions.'
  chcon u:object_r:system_file:s0 "$temp" || fail 'Cannot set the configuration SELinux label.'
  mv -f "$temp" "$CONFIG" || fail 'Cannot replace configuration.'
}

run_with_settings_lock() {
  # Android's /system/bin/sh closes descriptors opened by exec before running an external
  # command. Let flock open the lock itself and hold it until the settings command exits.
  if [ -x /data/adb/ksu/bin/busybox ]; then
    set -- /data/adb/ksu/bin/busybox flock "$@"
  elif [ -x /data/adb/magisk/busybox ]; then
    set -- /data/adb/magisk/busybox flock "$@"
  else
    set -- flock "$@"
  fi
  "$@"
}

apply_saved_config() {
  read_config || fail 'Settings were saved, but could not be read back.'
  # An ordered broadcast tells the caller if the running module accepted the values.
  result=$(timeout 5 am broadcast --user 0 --receiver-registered-only \
    -a io.github.misaka20002.gestureswitch.UPDATE_SETTINGS -p android \
    --es folded_mode "$folded" --es open_mode "$opened" --ei debounce_ms "$debounce" 2>/dev/null) ||
    return 1
  case "$result" in
    *'Broadcast completed: result=-1, data="gesture_switch.settings_updated"'*) return 0 ;;
    *) return 1 ;;
  esac
}

# Keep saving and broadcasting in the same critical section so the running module
# receives updates in the same order they reach disk.
case "${1:-}" in
  save|apply|toggle)
    run_with_settings_lock "$MODDIR/.settings.lock" sh "$0" --locked "$@"
    update_status=$?
    [ "$update_status" -eq 0 ] || fail 'Could not complete the settings update.'
    exit 0
    ;;
  --locked)
    shift
    case "${1:-}" in
      save|apply|toggle) ;;
      *) fail 'Invalid settings update.' ;;
    esac
    ;;
esac

case "$1:$#" in
  status:1)
    read_config || fail 'Cannot read configuration.'
    query_modes
    printf '%s\n%s\n%s\n%s\n' "$folded" "$opened" "$debounce" "$available"
    ;;
  open-mode:1)
    read_config || fail 'Cannot read configuration.'
    printf '%s\n' "$opened"
    ;;
  save:4)
    case "$4" in
      ''|*[!0-9]*) fail 'Debounce must be an integer from 0 to 5000 ms.' ;;
    esac
    if [ "${#4}" -gt 4 ] || [ "$4" -gt 5000 ]; then
      fail 'Debounce must be an integer from 0 to 5000 ms.'
    fi
    save_config "$2" "$3" "$4"
    if apply_saved_config; then printf 'applied\n'; else printf 'saved\n'; fi
    ;;
  apply:1)
    apply_saved_config || fail 'The running module did not acknowledge the saved settings.'
    printf 'Settings applied.\n'
    ;;
  toggle:1)
    read_config || fail 'Cannot read configuration.'
    if [ "$folded:$opened" = threebutton:gestural ]; then
      folded=gestural
      opened=threebutton
    else
      folded=threebutton
      opened=gestural
    fi
    save_config "$folded" "$opened" ''
    if apply_saved_config; then
      printf 'Folded: %s\nOpen: %s\nSaved and applied.\n' "$folded" "$opened"
    else
      printf 'Folded: %s\nOpen: %s\nSaved. The running module did not acknowledge the change.\n' "$folded" "$opened"
    fi
    ;;
  *)
    fail 'Usage: settings.sh status | open-mode | save FOLDED OPEN DEBOUNCE_MS | apply | toggle'
    ;;
esac

#!/bin/bash
# WearTube on-watch test helper.
#
# HARD RULE: only ever talk to the watch. The user's PHONE also advertises adb on
# this LAN and has been selected by mistake before (a release APK was installed to
# it). So every candidate is verified by ro.serialno before use — no exceptions,
# no "first device on the network" shortcuts.
PKG=com.wateruse.weartube
WATCH_SERIAL="${WATCH_SERIAL:-RFGL73966FN}"   # Galaxy Watch Ultra 2, SM-L715U
SCRATCH="${WT_SCRATCH:-$(ls -d /private/tmp/claude-501/-Users-wateruse-CLAUDE/*/scratchpad 2>/dev/null | tail -1)}"

# true only if $1 is genuinely the watch
wt_is_watch() {
  [ -n "$1" ] || return 1
  local s
  s=$(adb -s "$1" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
  [ "$s" = "$WATCH_SERIAL" ]
}

wt_find() {
  local cand
  # 1. anything already connected that IS the watch
  for cand in $(adb devices | awk '$2=="device"{print $1}'); do
    wt_is_watch "$cand" && { echo "$cand"; return 0; }
  done
  # 2. mdns entries — connect, then verify serial before accepting
  for cand in $(adb mdns services 2>/dev/null | grep -oE '(adb-[A-Za-z0-9_.-]+|19[0-9.]+:[0-9]+)' | sort -u); do
    adb connect "$cand" >/dev/null 2>&1
    sleep 1
    wt_is_watch "$cand" && { echo "$cand"; return 0; }
  done
  return 1
}

W=$(wt_find) || { echo "WATCH UNREACHABLE (serial $WATCH_SERIAL not found)"; exit 3; }
export W
wake()  { adb -s "$W" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; }
tap()   { wake; adb -s "$W" shell input tap "$1" "$2"; }
swipe() { adb -s "$W" shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
back()  { adb -s "$W" shell input keyevent KEYCODE_BACK; }
shot()  { adb -s "$W" exec-out screencap -p > "$SCRATCH/$1"; }
logc()  { adb -s "$W" logcat -c; }
logs()  { adb -s "$W" logcat -d | grep -E "WTStream|WTAuth|AndroidRuntime|FATAL|Playback error"; }
launch(){ wake; adb -s "$W" shell am force-stop $PKG; adb -s "$W" shell am start -n $PKG/.MainActivity "$@" >/dev/null; }
focus() { adb -s "$W" shell "dumpsys window | grep mCurrentFocus" | sed 's/.*mCurrentFocus=//'; }
alive() { adb -s "$W" shell pidof $PKG >/dev/null 2>&1 && echo ALIVE || echo DEAD; }
# install, but only ever to the verified watch
wtinstall() { adb -s "$W" install -r "$1" 2>&1 | tail -1; }
"$@"

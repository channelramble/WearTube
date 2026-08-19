#!/bin/bash
source ~/CLAUDE/WearTube/tools/wt.sh true
PKG=com.wateruse.weartube
S="${WT_SCRATCH:-/private/tmp/claude-501/-Users-wateruse-CLAUDE/c2b44236-a23a-40b8-9d38-1d4679bfb14e/scratchpad}"
run() { # name route waitsec
  adb -s $W logcat -c
  adb -s $W shell input keyevent KEYCODE_WAKEUP >/dev/null
  adb -s $W shell am force-stop $PKG
  adb -s $W shell am start -n $PKG/.MainActivity --es open_tab "$2" >/dev/null
  sleep "${3:-8}"
  local crash; crash=$(adb -s $W logcat -d | grep -cE "FATAL EXCEPTION|AndroidRuntime: .*Exception")
  local pid; pid=$(adb -s $W shell pidof $PKG | tr -d '\r')
  adb -s $W exec-out screencap -p > "$S/nav_$1.png"
  printf "%-24s crash=%s alive=%s\n" "$1" "$crash" "$([ -n "$pid" ] && echo yes || echo NO)"
}

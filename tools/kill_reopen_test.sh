#!/usr/bin/env bash
# Milestone 6 check, for the emulator or a phone with the dev stub (or a real key) configured.
# Each round: swipe Chỉ Đây away from recents, reopen it, press "Bắt đầu" (app goes to the
# back), open Chrome, tap the bubble, and require a ring plus speech in the log.
#
#   bash tools/kill_reopen_test.sh [rounds] [url] [screenshot-dir]
#
# Needs: adb on PATH, the accessibility service already on, a goal target the model (or the
# stub's /pick regex) can find on the page.
set -u
ROUNDS="${1:-5}"
URL="${2:-https://chinhphu.vn}"
SHOTS="${3:-}"
PKG=vn.chiday.app
pass=0

bubble_centre() {
  local f
  f=$(adb shell dumpsys window windows | grep -o "ChiDayBubble, frame=\[[0-9,]*\]" | head -1 | grep -o "[0-9,]*\]" | tr -d ']')
  if [ -z "$f" ]; then
    # Android 15+ (seen on a Galaxy A56, Android 16) prints "frame=[l,t][r,b]" inside the window's block.
    f=$(adb shell dumpsys window windows | sed -n '/Window{.*ChiDayBubble}:/,/Window #/p' \
      | grep -o ' frame=\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 \
      | tr -c '0-9\n' ',' | sed 's/^,*//; s/,,*/,/g; s/,$//')
  fi
  [ -z "$f" ] && return 1
  IFS=, read -r l t r b <<< "$f"
  echo "$(( (l + r) / 2 )) $(( (t + b) / 2 ))"
}

for i in $(seq 1 "$ROUNDS"); do
  echo "== round $i"
  pid_before=$(adb shell pidof $PKG | tr -d '\r')

  # 1. Bring the app to the front so it is the top card, then swipe it away from recents.
  adb shell am start -n $PKG/.MainActivity >/dev/null
  sleep 2
  adb shell input keyevent KEYCODE_APP_SWITCH
  sleep 2
  adb shell input swipe 540 1300 540 100 120
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  # Samsung One UI lays recents out sideways and the bubble floats over them, so the swipe
  # above can miss the app's card. Remove the task directly then (what a recents swipe does).
  tid=$(adb shell dumpsys activity recents | grep -oE "Task\{[0-9a-f]+ #[0-9]+ type=standard A=[0-9]+:$PKG" | grep -oE '#[0-9]+' | tr -d '#' | head -1)
  [ -n "$tid" ] && adb shell am stack remove "$tid" >/dev/null 2>&1 && sleep 2
  tasks=$(adb shell dumpsys activity recents | grep -c "A=.*$PKG" | tr -d '\r')
  pid_after=$(adb shell pidof $PKG | tr -d '\r')
  echo "   swiped away: recents entries for app=$tasks pid $pid_before -> ${pid_after:-none}"

  # 2. Reopen from the launcher intent. No re-setup is done here on purpose.
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  enabled=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
  [ -n "$SHOTS" ] && adb exec-out screencap -p > "$SHOTS/m6_home_$i.png"

  # 3. Open the target page and tap the bubble.
  adb shell am start -a android.intent.action.VIEW -d "$URL" com.android.chrome >/dev/null 2>&1
  sleep 8
  if ! c=$(bubble_centre); then
    echo "   FAIL: bubble window not found (service enabled: $enabled)"
    continue
  fi
  adb logcat -c
  adb shell input tap $c
  sleep 5
  log=$(adb logcat -d -s ChiDay)
  [ -n "$SHOTS" ] && adb exec-out screencap -p > "$SHOTS/m6_ring_$i.png"
  ring=$(echo "$log" | grep -c "ring on node")
  speech=$(echo "$log" | grep -c "speech started")
  overlay=$(adb shell dumpsys window windows | grep -c "ChiDayHighlight, frame\|Window{.*ChiDayHighlight}")
  # 4. End the session so the next round starts clean.
  adb shell input tap $c
  sleep 1

  if [ "$ring" -ge 1 ] && [ "$speech" -ge 1 ] && [ "$overlay" -ge 1 ]; then
    echo "   PASS: ring=$ring speech=$speech overlay window present"
    pass=$((pass + 1))
  else
    echo "   FAIL: ring=$ring speech=$speech overlay=$overlay"
    echo "$log" | grep -v " node " | tail -5
  fi
done

echo "RESULT: $pass/$ROUNDS rounds passed"
[ "$pass" -eq "$ROUNDS" ]

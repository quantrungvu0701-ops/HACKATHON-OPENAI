#!/usr/bin/env bash
# Milestone 7 check on the emulator with the dev stub: ONE bubble tap, then a 4-step flow in
# the Android Settings app is guided with no further bubble taps. After each ring this script
# taps the ringed element itself (standing in for the user's finger).
#
#   python tools/stub/stub_server.py &      # stub must be running and configured in the app
#   bash tools/follow_mode_test.sh
#
# English system language is assumed (the emulator default).
set -u
STEPS=4
curl -s "http://localhost:8787/pick?q=Search%20settings~Network%20%26%20internet;;Hotspot~Data%20Saver;;Use%20Data%20Saver~Unrestricted%20data;;~Navigate%20up" >/dev/null || {
  echo "stub server is not running on :8787"; exit 2; }

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

adb shell am force-stop com.android.settings
adb shell am start -a android.settings.SETTINGS >/dev/null
sleep 3
c=$(bubble_centre) || { echo "FAIL: no bubble"; exit 1; }
adb logcat -c
echo "single bubble tap at $c"
adb shell input tap $c

seen=0
for step in $(seq 1 $STEPS); do
  ring=""
  for _ in $(seq 1 20); do
    sleep 1
    ring=$(adb logcat -d -s ChiDay | grep "ring on node" | sed -n "$((seen + 1))p")
    [ -n "$ring" ] && break
  done
  if [ -z "$ring" ]; then
    echo "FAIL at step $step: no new ring within 20 s"
    adb logcat -d -s ChiDay | grep -v " node " | tail -6 | cut -c32-
    adb shell input tap $c
    exit 1
  fi
  seen=$((seen + 1))
  rect=$(echo "$ring" | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]")
  read -r l t r b <<< "$(echo "$rect" | tr -c '0-9' ' ')"
  echo "step $step: ring at $rect"
  [ "$step" -lt "$STEPS" ] && adb shell input tap $(( (l + r) / 2 )) $(( (t + b) / 2 ))
done

taps=$(adb logcat -d -s ChiDay | grep -c "bubble tap")
auto=$(adb logcat -d -s ChiDay | grep -c "follow mode")
speech=$(adb logcat -d -s ChiDay | grep -c "speech started")
adb shell input tap $c   # end the session
adb shell am force-stop com.android.settings
echo "RESULT: rings=$seen bubble_taps=$taps auto_reanalyses=$auto spoken=$speech"
[ "$seen" -eq "$STEPS" ] && [ "$taps" -eq 1 ] && echo PASS

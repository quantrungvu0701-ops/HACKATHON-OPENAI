#!/usr/bin/env bash
# Privacy filter check on the emulator or phone (app installed, service on, any model or none).
# Opens a test page in Chrome with an input field, a password field and personal numbers,
# types into the fields, taps the bubble, and checks that the node list logged by the debug
# build (the same list sent to the model) contains none of the typed or personal values.
set -u
export MSYS_NO_PATHCONV=1

TYPED="NguyenVanAn"
SECRET="Matkhau123"
PAGE='<html><head><meta name=viewport content="width=device-width"></head><body style="font-size:22px">
<p>Ho ten <input id=name placeholder="Nhap ho ten" style="font-size:22px"></p>
<p>Mat khau <input id=pw type=password placeholder="Mat khau" style="font-size:22px"></p>
<p>SDT 0912 345 678</p><p>Email an.nguyen@example.com</p><p>CCCD 079203001234</p>
<button>Tiep tuc</button></body></html>'
# Chrome will not open data: URLs from an intent, so serve the page from this computer.
DIR=$(mktemp -d); echo "$PAGE" > "$DIR/privacy.html"
python -m http.server 8765 --directory "$(cygpath -w "$DIR" 2>/dev/null || echo "$DIR")" >/dev/null 2>&1 & SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$DIR"' EXIT
adb reverse tcp:8765 tcp:8765 >/dev/null
URL="http://localhost:8765/privacy.html"
sleep 1

bubble_centre() {
  local f
  f=$(adb shell dumpsys window windows | grep -o "ChiDayBubble, frame=\[[0-9,]*\]" | head -1 | grep -o "[0-9,]*\]" | tr -d ']')
  [ -z "$f" ] && f=$(adb shell dumpsys window windows | sed -n '/Window{.*ChiDayBubble}:/,/Window #/p' \
      | grep -o ' frame=\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1 | tr -c '0-9\n' ',' | sed 's/^,*//; s/,,*/,/g; s/,$//')
  [ -z "$f" ] && return 1
  IFS=, read -r l t r b <<< "$f"
  echo "$(( (l + r) / 2 )) $(( (t + b) / 2 ))"
}

centre_of() { # centre of the first uiautomator node whose attributes match $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "$1" | grep -o 'bounds="[^"]*"' \
    | tr -c '0-9\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

adb shell am start -a android.intent.action.VIEW -d "$URL" com.android.chrome >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -n "$(centre_of 'resource-id="name"')" ] && break; sleep 1; done
sleep 1
adb shell input tap $(centre_of 'resource-id="name"') && sleep 1 && adb shell input text "$TYPED"
adb shell input tap $(centre_of 'password="true"') && sleep 1 && adb shell input text "$SECRET"
adb shell input keyevent 111   # hide keyboard
sleep 1

c=$(bubble_centre) || { echo "FAIL: no bubble"; exit 1; }
adb logcat -c
adb shell input tap $c
sleep 4
log=$(adb logcat -d -s ChiDay)
adb shell input tap $c   # end the session again

nodes=$(echo "$log" | grep " node ")
echo "$nodes" | cut -c50- | head -20
fail=0
[ -z "$nodes" ] && { echo "FAIL: no node list logged"; exit 1; }
for leak in "$TYPED" "$SECRET" "0912" "345 678" "079203001234" "an.nguyen" "example.com"; do
  if echo "$log" | grep -q "$leak"; then echo "LEAK: $leak"; fail=1; fi
done
echo "$nodes" | grep '"hint":"Nhap ho ten"' | grep -q '"filled":true' || { echo "FAIL: name field missing or not marked filled"; fail=1; }
echo "$nodes" | grep -q '••••••' || { echo "FAIL: personal numbers not shown as masked"; fail=1; }
[ $fail = 0 ] && echo "PASS: no typed text or personal data in the node list" || exit 1

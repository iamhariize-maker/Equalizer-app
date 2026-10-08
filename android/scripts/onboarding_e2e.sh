#!/usr/bin/env bash
# CI emulator only: reinstalls Svan, then tests real first-run and session broadcasts.
set -euo pipefail
S=${1:-emulator-5554}
OUT=${2:-/tmp/onboarding-e2e}
APK=${3:-app/build/outputs/apk/debug/app-debug.apk}
mkdir -p "$OUT"
A=(adb -s "$S")
failure_shot() {
    local rc=$?
    if [[ "$rc" != 0 ]]; then
        "${A[@]}" exec-out screencap -p > "$OUT/failure.png" || true
        printf 'FAIL onboarding test exited %s\n' "$rc" >> "$OUT/results.txt"
    fi
}
trap failure_shot EXIT
CAP=app.svan.testsource.capturable
eq() { "${A[@]}" shell am start -n app.svan/.Command --es cmd "$@" >/dev/null; }
tone() { "${A[@]}" shell am start -n "$CAP"/app.svan.testsource.ToneActivity "$@" >/dev/null; }
state() {
    "${A[@]}" shell run-as app.svan rm -f files/onboarding-state.json
    eq onboarding_state
    for ((i=0;i<15;i++)); do
        if "${A[@]}" shell run-as app.svan cat files/onboarding-state.json > "$OUT/state.json" 2>/dev/null && [[ -s "$OUT/state.json" ]]; then return; fi
        sleep 1
    done
    return 1
}
assert_state() {
    local name=$1 expression=$2
    local passed=0
    for ((attempt=0;attempt<15;attempt++)); do
      state
      if python3 - "$OUT/state.json" "$expression" <<'PY'
import json,sys
s=json.load(open(sys.argv[1]));ok=eval(sys.argv[2],{'__builtins__':{}},{'s':s})
sys.exit(0 if ok else 1)
PY
      then passed=1; break; fi
      sleep 1
    done
    cat "$OUT/state.json"
    if [[ "$passed" == 1 ]]; then printf 'PASS %s\n' "$name" >> "$OUT/results.txt"
    else printf 'FAIL %s\n' "$name" >> "$OUT/results.txt"; exit 1; fi
}
tap() {
    "${A[@]}" shell uiautomator dump /sdcard/onboarding.xml >/dev/null 2>&1
    "${A[@]}" shell cat /sdcard/onboarding.xml > "$OUT/ui.xml"
    local point
    point=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text')==sys.argv[2] and n.get('enabled')=='true':
  p=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
  if len(p)==4: print((p[0]+p[2])//2,(p[1]+p[3])//2);sys.exit(0)
sys.exit(1)
PY
    )
    read -r x y <<< "$point"
    "${A[@]}" shell input tap "$x" "$y"
}
: > "$OUT/results.txt"
# This prelude intentionally tests basic detection. A previously authorized Shizuku
# manager can survive app reinstall and automatically reconnect the shell helper.
"${A[@]}" shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
tone --ez stop true || true
"${A[@]}" uninstall app.svan >/dev/null 2>&1 || true
"${A[@]}" install "$APK" >/dev/null
"${A[@]}" install -r testsource/build/outputs/apk/capturable/debug/testsource-capturable-debug.apk >/dev/null
"${A[@]}" shell pm revoke app.svan android.permission.DUMP
eq onboarding_state
sleep 7
assert_state 'fresh install uses System effects, Flat/0 dB, no capture or automatic tonal layer' \
    "s['system'] and not s['capture'] and s['engineMode']=='SYSTEM_ONLY' and s['preset']=='Flat' and s['preamp']==0 and not s['smart'] and not s['dump']"
assert_state 'nothing playing has no lazy setup prompt' "s['kind']=='IDLE' and not s['prompt']"
"${A[@]}" exec-out screencap -p > "$OUT/first-run.png"
tap 'Got it'
tone --ef freq 1000 --ef amp 0.1 --ez broadcast true
sleep 7
assert_state 'broadcast player is routed to System effects with no detection setup' "s['kind']=='ROUTED' and not s['prompt'] and not s['dump'] and not s['capture']"
tap 'Hi-Fi'
sleep 2
"${A[@]}" exec-out screencap -p > "$OUT/status-system-effects.png"
tone --ez stop true
sleep 3
tone --ef freq 1000 --ef amp 0.1 --ez broadcast false
sleep 7
assert_state 'non-broadcast playback offers the contextual setup prompt' "s['kind']=='UNREACHABLE' and s['prompt'] and not s['dump']"
"${A[@]}" exec-out screencap -p > "$OUT/status-unreachable.png"
tap 'Not now'
assert_state 'dismissing setup does not change audio mode' "not s['prompt'] and s['engineMode']=='SYSTEM_ONLY' and not s['capture']"
"${A[@]}" shell am force-stop app.svan
eq onboarding_state
sleep 6
assert_state 'dismissal persists after reopening' "not s['prompt']"
eq onboarding_reset_prompts
sleep 2
assert_state 'hidden setup prompt can be restored' "s['prompt']"
tap 'Fix music detection'
sleep 2
"${A[@]}" exec-out screencap -p > "$OUT/wizard-live.png"
tap 'Back to Svan'
tone --ez stop true
sleep 6
assert_state 'stopped playback removes the prompt' "s['kind']=='IDLE' and not s['prompt']"
tap 'Hi-Fi'
"${A[@]}" exec-out screencap -p > "$OUT/status-idle.png"
cat "$OUT/results.txt"

bash scripts/detection_fallbacks.sh "$S" "$OUT/fallbacks"

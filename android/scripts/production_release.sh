#!/usr/bin/env bash
# Exercise the production R8 build through its public UI, with automation extras disabled.
set -euo pipefail
S=${1:-emulator-5554}; APK=${2:?production APK required}; OUT=${3:?output directory required}
A=(adb -s "$S"); mkdir -p "$OUT"; : > "$OUT/results.txt"
"${A[@]}" uninstall app.svan >/dev/null 2>&1 || true
"${A[@]}" install "$APK" >/dev/null
"${A[@]}" shell pm grant app.svan android.permission.POST_NOTIFICATIONS
"${A[@]}" logcat -c
"${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd preset --es quality INVALID >/dev/null
sleep 8
[[ -n $("${A[@]}" shell pidof app.svan) ]]
! "${A[@]}" logcat -d -s EqSpike:I | grep -q 'CMD preset'
echo 'PASS production ignores exported automation commands' >> "$OUT/results.txt"
tap() {
    "${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1
    local point
    point=$("${A[@]}" shell cat /sdcard/svan-production.xml | python3 -c 'import re,sys,xml.etree.ElementTree as E; n=next((n for n in E.fromstring(sys.stdin.read()).iter("node") if n.attrib.get("text")==sys.argv[1]),None); b=list(map(int,re.findall(r"\d+",n.attrib["bounds"]))) if n is not None else []; print(f"{(b[0]+b[2])//2} {(b[1]+b[3])//2}" if b else "")' "$1")
    [[ -n "$point" ]] || { echo "FAIL production control missing: $1" >> "$OUT/results.txt"; exit 1; }
    "${A[@]}" shell input tap $point; sleep 2
}
tap Presets
"${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1
"${A[@]}" shell cat /sdcard/svan-production.xml | grep -q 'Export settings'
"${A[@]}" shell cat /sdcard/svan-production.xml | grep -q 'Restore settings'
"${A[@]}" exec-out screencap -p > "$OUT/settings-migration.png"
echo 'PASS production provides settings migration controls' >> "$OUT/results.txt"
tap Lab; tap 'Run engine checks'
ready=''
for ((attempt=0;attempt<120;attempt++)); do
    ready=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/^.*QUALITY_LAB_READY //p' | tail -1)
    [[ -n "$ready" ]] && break
    if "${A[@]}" logcat -d -s EqSpike:I | grep -q QUALITY_LAB_FAILED; then "${A[@]}" logcat -d -s EqSpike:I; exit 1; fi
    sleep 1
done
[[ -n "$ready" ]] || { echo 'FAIL production engine checks timed out' >> "$OUT/results.txt"; exit 1; }
printf '%s\n' "$ready" > "$OUT/lab.json"
python3 - "$OUT/lab.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert d['truePeakBefore']>1.3 and d['truePeakAfter']<.93
assert -1.7<d['dynamicDb']<-1.2 and d['calibrationRmsDb']<.4
assert d['matchDb']<=.1 and d['trimOriginalDb']<=0 and d['trimProcessedDb']<=0
PY
echo 'PASS production native quality and matched listening checks' >> "$OUT/results.txt"
tap 'Blind listening'
"${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1
"${A[@]}" shell cat /sdcard/svan-production.xml | grep -q 'Choose WAV'
"${A[@]}" exec-out screencap -p > "$OUT/blind-listening.png"
! "${A[@]}" logcat -d | grep -qE 'FATAL EXCEPTION|UnsatisfiedLinkError|NoSuchMethodError'
echo 'PASS production blind listening dialog launches without crash' >> "$OUT/results.txt"
cat "$OUT/results.txt"

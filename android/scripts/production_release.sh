#!/usr/bin/env bash
# Exercise the production R8 build through its public UI, with automation extras disabled.
set -euo pipefail
S=${1:-emulator-5554}; APK=${2:?production APK required}; OUT=${3:?output directory required}
A=(adb -s "$S"); mkdir -p "$OUT"; : > "$OUT/results.txt"
diagnostics() {
    local result=$?
    "${A[@]}" logcat -d > "$OUT/logcat.txt" 2>&1 || true
    "${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1 || true
    "${A[@]}" shell cat /sdcard/svan-production.xml > "$OUT/final-ui.xml" 2>/dev/null || true
    "${A[@]}" exec-out screencap -p > "$OUT/final-ui.png" 2>/dev/null || true
    exit "$result"
}
trap diagnostics EXIT
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
    point=$("${A[@]}" shell cat /sdcard/svan-production.xml | python3 -c 'import re,sys,xml.etree.ElementTree as E; n=next((n for n in E.fromstring(sys.stdin.read()).iter("node") if n.attrib.get("text")==sys.argv[1] and n.attrib.get("bounds")!="[0,0][0,0]"),None); b=list(map(int,re.findall(r"\d+",n.attrib["bounds"]))) if n is not None else []; print(f"{(b[0]+b[2])//2} {(b[1]+b[3])//2}" if b else "")' "$1")
    [[ -n "$point" ]] || { echo "FAIL production control missing: $1" >> "$OUT/results.txt"; exit 1; }
    "${A[@]}" shell input tap $point; sleep 2
}
visible() {
    "${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1
    "${A[@]}" shell cat /sdcard/svan-production.xml | python3 -c 'import sys,xml.etree.ElementTree as E;sys.exit(not any(sys.argv[1] in n.attrib.get("text","") and n.attrib.get("bounds")!="[0,0][0,0]" for n in E.fromstring(sys.stdin.read()).iter("node")))' "$1"
}
scroll_to() {
    for ((i=0;i<12;i++)); do
        visible "$1" && return 0
        if [[ ${2:-down} == up ]]; then
            "${A[@]}" shell input swipe 160 190 160 480 350
        else
            "${A[@]}" shell input swipe 160 480 160 190 350
        fi
        sleep 1
    done
    echo "FAIL production capture UI text missing: $1" >> "$OUT/results.txt"; exit 1
}
tap Presets
"${A[@]}" shell uiautomator dump /sdcard/svan-production.xml >/dev/null 2>&1
"${A[@]}" shell cat /sdcard/svan-production.xml | grep -q 'Export settings'
"${A[@]}" shell cat /sdcard/svan-production.xml | grep -q 'Restore settings'
"${A[@]}" exec-out screencap -p > "$OUT/settings-migration.png"
echo 'PASS production provides settings migration controls' >> "$OUT/results.txt"
tap Lab
tap Measure
scroll_to 'Device probes and engine log'
tap 'Device probes and engine log'
tap 'Run engine checks'
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
! "${A[@]}" logcat -d | grep -qE 'FATAL EXCEPTION|UnsatisfiedLinkError|NoSuchMethodError'
echo 'PASS production engine checks finish without a crash' >> "$OUT/results.txt"
# Exercise the delivered R8 routing code through public controls, with PHONE_PREVIEW=false.
"${A[@]}" shell pm grant app.svan android.permission.DUMP
"${A[@]}" shell pm grant app.svan android.permission.RECORD_AUDIO
"${A[@]}" shell appops set app.svan PROJECT_MEDIA allow
CAP=app.svan.testsource.capturable
BLK=app.svan.testsource.blocked
tone() { "${A[@]}" shell am start -W -n "$1"/app.svan.testsource.ToneActivity "${@:2}" >/dev/null; }
measure() { python3 "$(dirname "$0")/host_audio_level.py" --capture-wav "${QEMU_WAV_PATH:?host audio required}" "$OUT/$1.float32le" > "$OUT/$1.db"; }
tone "$CAP" --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez component true
sleep 4
measure capture-flat
tap Hi-Fi
# Fresh installs use System effects only. Select the GLOBAL Auto row by its
# unique description, rather than one of the per-app Auto buttons.
scroll_to 'Audiophile engine for apps that allow capture, system effects for the rest.'
tap 'Audiophile engine for apps that allow capture, system effects for the rest.'
scroll_to 'Start audiophile engine' up; tap 'Start audiophile engine'
for ((attempt=0;attempt<45;attempt++)); do
    visible 'Audiophile engine connected' && break
    # Starting capture can leave the engine summary above the current viewport.
    # Inspect the same public status after scrolling it back into view.
    "${A[@]}" shell input swipe 160 190 160 480 350
    sleep 1
done
visible 'Audiophile engine connected'
sleep 3
"${A[@]}" logcat -d -s EqSpike:I > "$OUT/capture-log.txt"
python3 - "$OUT/capture-log.txt" "$CAP" <<'PY'
import re,sys
s=open(sys.argv[1]).read();pkg=re.escape(sys.argv[2])
assert re.search(rf'capture check: {pkg} .*phase=unmuted result=audio frames=[1-9]\d*',s)
p=re.search(rf'capture check: {pkg} .*phase=muted result=audio frames=[1-9]\d* .*elapsedMs=(\d+)',s)
assert p and int(p[1])>=200
assert re.search(r'source=captured capturedFrames=[1-9]\d*',s)
PY
"${A[@]}" exec-out screencap -p > "$OUT/native-capture.png"
echo 'PASS production public UI starts capture with settled mute proof and real frames' >> "$OUT/results.txt"
measure capture-native
python3 - "$OUT/capture-flat.db" "$OUT/capture-native.db" <<'PY'
import sys
a,b=map(lambda p:float(open(p).read()),sys.argv[1:])
assert abs(b-a)<=.75,(a,b)
PY
echo 'PASS production native capture replays one measured audio copy' >> "$OUT/results.txt"
scroll_to 'Stop audiophile engine' up; tap 'Stop audiophile engine'
tone "$CAP" --ez stop true
sleep 3
tone "$BLK" --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez component true
sleep 4
scroll_to 'Start audiophile engine' up; tap 'Start audiophile engine'
scroll_to "This installed app's Android settings disable playback capture"
"${A[@]}" exec-out screencap -p > "$OUT/manifest-opt-out.png"
echo 'PASS production proves installed manifest opt-out without a silence verdict' >> "$OUT/results.txt"
measure capture-blocked
python3 - "$OUT/capture-flat.db" "$OUT/capture-blocked.db" <<'PY'
import sys
a,b=map(lambda p:float(open(p).read()),sys.argv[1:])
assert abs(b-a)<=.75,(a,b)
PY
! "${A[@]}" logcat -d | grep -qE 'FATAL EXCEPTION|UnsatisfiedLinkError|NoSuchMethodError'
echo 'PASS production capture-blocked source remains audible on one system-effects path' >> "$OUT/results.txt"
cat "$OUT/results.txt"

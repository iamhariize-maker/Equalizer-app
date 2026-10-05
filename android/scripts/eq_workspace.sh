#!/usr/bin/env bash
# Tests the user-visible EQ owner/layout state against final Android output.
set -eu
S=${1:-emulator-5554}; OUT=${2:-/tmp/eq-workspace}; A="adb -s $S"
CAP=app.svan.testsource.capturable
mkdir -p "$OUT"
: > "$OUT/results.txt"
eq() { $A shell am start -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
tap() {
  $A shell uiautomator dump /sdcard/eq-workspace.xml >/dev/null 2>&1
  local point
  point=$($A shell cat /sdcard/eq-workspace.xml | python3 -c 'import re,sys,xml.etree.ElementTree as E; n=next((n for n in E.fromstring(sys.stdin.read()).iter("node") if n.attrib.get("text")==sys.argv[1]),None); b=list(map(int,re.findall(r"\d+",n.attrib["bounds"]))) if n is not None else []; print(f"{(b[0]+b[2])//2} {(b[1]+b[3])//2}" if b else "")' "$1")
  [ -n "$point" ] || { echo "FAIL EQ UI control missing: $1" >> "$OUT/results.txt"; exit 1; }
  $A shell input tap $point; sleep 2
}
state() {
  $A logcat -c; eq eq_workspace
  for attempt in $(seq 1 10); do
    local value
    value=$($A logcat -d -s EqSpike:I | sed -n 's/.*EQ_WORKSPACE //p' | tail -1)
    if [ -n "$value" ]; then echo "$value" > "$OUT/$1.json"; return; fi
    sleep 1
  done
  echo "FAIL workspace state unavailable" >> "$OUT/results.txt"; exit 1
}
measure() {
  $A logcat -c; eq measure_mix --ef seconds 3
  for attempt in $(seq 1 15); do
    local value
    value=$($A logcat -d -s EqSpike:I | sed -n 's/.*MIX median=\([-0-9.]*\).*/\1/p' | tail -1)
    if [ -n "$value" ]; then echo "$value" > "$OUT/$1.level"; return; fi
    sleep 1
  done
  echo "FAIL workspace output measurement unavailable" >> "$OUT/results.txt"; exit 1
}
eq reset_sound; eq engine_mode --ez system_only true; eq gain_settings --ez headroom false --ez protection true
$A shell am start -n "$CAP"/app.svan.testsource.ToneActivity --ef freq 1000 --ef amp 0.1 --ez broadcast true >/dev/null
sleep 5; measure baseline
eq eq_band --ef frequency 1000 --ef gain 6; sleep 3; state manual; measure manual
tap EQ; tap 'Svaresa EQ'
eq svaramanas --ez on true --es mode SVARESA --es night OFF --ez volume_aware false --ez route_aware false --ez auto_headphone false
sleep 4; state automatic; measure automatic
tap Graphic
sleep 4; state graphic; measure graphic
# Preferences are bounded, reflected in the actual fitted cascade, and persisted locally.
eq eq_personal_gain --ei index 17 --ef gain 2
sleep 4; state preference; measure preference
$A shell am force-stop app.svan
$A shell am start -n app.svan/.MainActivity >/dev/null
sleep 8; state restart
tap EQ; tap 'Your EQ'
sleep 4; state restored; measure restored
python3 - "$OUT" <<'PY' | tee -a "$OUT/results.txt"
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);failures=0
load=lambda n:json.loads((p/f'{n}.json').read_text())
level=lambda n:float((p/f'{n}.level').read_text())
manual,auto,graphic,pref,restart,restored=map(load,['manual','automatic','graphic','preference','restart','restored'])
def check(name,ok,detail):
 global failures
 print(('PASS ' if ok else 'FAIL ')+name+' — '+detail)
 failures+=not ok
check('manual band changes actual output',abs(level('manual')-level('baseline')-6)<1, f"delta={level('manual')-level('baseline'):.2f} dB")
check('Svaresa owns bands without hidden manual boost',auto['state']['smartEqControl'] and auto['state']['bands']==manual['state']['bands'] and abs(level('automatic')-level('baseline'))<1, f"delta={level('automatic')-level('baseline'):.2f} dB")
check('graphic faders are the applied automatic filters',len(graphic['smartBands'])==31 and graphic['smartBands']==graphic['appliedBands'] and abs(level('graphic')-level('automatic'))<1, f"{len(graphic['smartBands'])} filters; delta={level('graphic')-level('automatic'):.2f} dB")
expected=pref['response']-graphic['response']; actual=level('preference')-level('graphic')
check('personal gain reaches measured output and its native prediction',abs(expected)>.15 and abs(actual-expected)<1, f"measured={actual:.2f}, predicted={expected:.2f} dB")
check('restart retains ownership layout and preferences',restart['state']['smartEqControl'] and restart['state']['smartEqMode']=='GRAPHIC' and restart['state']['smartGraphicCount']==31 and restart['state']['smartEqOffsets']==pref['state']['smartEqOffsets'] and abs(restart['response']-pref['response'])<.2, 'saved preferences, actual response reproduced')
check('manual override restores original curve and output',not restored['state']['smartEqControl'] and restored['state']['bands']==manual['state']['bands'] and abs(level('restored')-level('manual'))<1, f"delta={level('restored')-level('manual'):.2f} dB")
sys.exit(bool(failures))
PY
eq reset_sound
$A shell am start -n "$CAP"/app.svan.testsource.ToneActivity --ez stop true >/dev/null

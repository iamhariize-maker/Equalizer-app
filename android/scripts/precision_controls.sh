#!/usr/bin/env bash
# Real touch gestures, independent of the mathematical detent unit tests.
set -eu
S=${1:-emulator-5554}; OUT=${2:-/tmp/precision-controls}; A="adb -s $S"
mkdir -p "$OUT"; : > "$OUT/results.txt"
eq() { $A shell am start -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
read -r W H < <($A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
node() {
  $A shell uiautomator dump /sdcard/precision-ui.xml >/dev/null 2>&1
  $A shell cat /sdcard/precision-ui.xml > "$OUT/last-ui.xml"
  python3 -c 'import re,sys,xml.etree.ElementTree as E; attr,label=sys.argv[2:]; n=next((n for n in E.parse(sys.argv[1]).getroot().iter("node") if (label in n.attrib.get(attr,"") if attr=="content-desc" else n.attrib.get(attr)==label)),None); print(" ".join(re.findall(r"\d+",n.attrib["bounds"])) if n is not None else "")' "$OUT/last-ui.xml" "$1" "$2"
}
tap() {
  local b; b=$(node text "$1")
  [ -n "$b" ] || { echo "FAIL missing control $1" >> "$OUT/results.txt"; exit 1; }
  read -r x1 y1 x2 y2 <<< "$b"; $A shell input tap $(((x1+x2)/2)) $(((y1+y2)/2)); sleep 1
}
find_control() {
  local b
  for attempt in $(seq 1 12); do
    b=$(node content-desc "$1")
    if [ -n "$b" ]; then
      read -r x1 y1 x2 y2 <<< "$b"
      # A partly visible lazy-list item cannot reliably receive a drag.
      if [ "$y1" -ge "$((H/12))" ] && [ "$y2" -lt "$((H*7/8))" ]; then echo "$b"; return; fi
    fi
    # Scroll in the card margin. A vertical swipe through a fader edits it.
    if [ -n "$b" ] && [ "$y1" -lt "$((H/12))" ]; then
      $A shell input swipe $((W/20)) $((H/3)) $((W/20)) $((H/2)) 400
    else
      $A shell input swipe $((W/20)) $((H*3/4)) $((W/20)) $((H*7/12)) 400
    fi
    sleep 1
  done
  $A exec-out screencap -p > "$OUT/missing-target.png"
  state missing
  echo "FAIL missing gesture target $1" >> "$OUT/results.txt"; exit 1
}
state() {
  $A logcat -c; eq eq_workspace
  for attempt in $(seq 1 10); do
    if $A logcat -d -s EqSpike:I | grep -q EQ_WORKSPACE_READY; then
      $A shell run-as app.svan cat files/eq-workspace.json > "$OUT/$1.json"; return
    fi
    sleep 1
  done
  echo "FAIL no gesture state" >> "$OUT/results.txt"; exit 1
}
# A fresh debug install also lets this check run before the longer audio suite.
$A uninstall app.svan >/dev/null 2>&1 || true
$A install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A shell pm grant app.svan android.permission.DUMP
$A shell am start -n app.svan/.MainActivity >/dev/null
sleep 8
eq reset_sound; eq eq_control --ez auto false --ez graphic true --ei count 10
tap EQ
b=$(find_control '31 Hz gain'); read -r x1 y1 x2 y2 <<< "$b"
X=$(((x1+x2)/2)); T=$((y1+(y2-y1)/5)); D=$(((y2-y1)/8))
state fader_before
# Grab well away from the thumb and move DOWN: relative motion must reduce gain.
$A shell input swipe "$X" "$T" "$X" $((T+D)) 500; sleep 2; state fader_drag
$A shell input tap "$X" "$T"; sleep 1; state fader_tap
$A exec-out screencap -p > "$OUT/fader.png"
eq eq_band --ef frequency 1000 --ef gain 6
b=$(find_control Gain); read -r x1 y1 x2 y2 <<< "$b"
Y=$(((y1+y2)/2)); X=$((x1+(x2-x1)/5)); DX=$(((x2-x1)/12))
state slider_before
$A shell input tap $((x2-(x2-x1)/20)) "$Y"; sleep 1; state slider_tap
# Touch left of its thumb, then drag right: this must increase the existing gain.
$A shell input swipe "$X" "$Y" $((X+DX)) "$Y" 500; sleep 2; state slider_drag
$A exec-out screencap -p > "$OUT/slider.png"
tap Sound
b=$(find_control Amount); read -r x1 y1 x2 y2 <<< "$b"
X=$(((x1+x2)/2)); Y=$(((y1+y2)/2)); DX=$(((x2-x1)/3))
state knob_before
$A shell input swipe "$X" "$Y" $((X+DX)) "$Y" 600; sleep 2; state knob_sideways
# A real clockwise quarter-turn, beginning at the top of the rim.
R=$(((x2-x1)*43/100))
$A shell input motionevent DOWN "$X" $((Y-R))
for degrees in 15 30 45 60 75 90; do
  read -r px py < <(python3 - "$X" "$Y" "$R" "$degrees" <<'PY'
import math,sys
x,y,r,d=map(float,sys.argv[1:]);a=math.radians(d)
print(round(x+r*math.sin(a)),round(y-r*math.cos(a)))
PY
  )
  $A shell input motionevent MOVE "$px" "$py"
done
$A shell input motionevent UP $((X+R)) "$Y"; sleep 2; state knob_rotary
$A exec-out screencap -p > "$OUT/knob.png"
# Vertical movement through the spindle must remain a page scroll.
$A shell input swipe "$X" "$Y" "$X" $((Y-(y2-y1))) 500; sleep 2; state knob_scroll
python3 - "$OUT" <<'PY' | tee -a "$OUT/results.txt"
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); failures=0
state=lambda n:json.loads((p/f'{n}.json').read_text())['state']
gain=lambda n:state(n)['gGains'][0]
bass=lambda n:state(n)['bass']['amt']
band_gain=lambda n:state(n)['bands'][0]['g']
def check(name,ok,detail):
 global failures
 print(('PASS ' if ok else 'FAIL ')+name+' — '+detail); failures+=not ok
delta=gain('fader_drag')-gain('fader_before')
check('fader grab uses relative motion without a touch-position jump',-2<delta<-.05,f'{delta:.2f} dB')
tick=gain('fader_tap')-gain('fader_drag')
check('fader tap advances one micro step',abs(tick-.1)<1e-6,f'{tick:.2f} dB')
g=gain('fader_drag')
check('fader release retains the tenth-dB detent',abs(g*10-round(g*10))<1e-6,f'{g:.2f} dB')
delta=band_gain('slider_tap')-band_gain('slider_before')
check('horizontal slider tap advances one micro step',abs(delta-.1)<1e-6,f'{delta:.2f} dB')
delta=band_gain('slider_drag')-band_gain('slider_tap')
check('horizontal slider grab adjusts the existing gain relatively',.05<delta<4,f'{delta:.2f} dB')
delta=bass('knob_sideways')-bass('knob_before')
check('knob sideways drag makes a small precise change',.05<delta<1.5,f'{delta:.2f} dB')
delta=bass('knob_rotary')-bass('knob_sideways')
check('clockwise rim rotation changes actual knob state',1<delta<3.8,f'{delta:.2f} dB')
check('vertical centre gesture scrolls without editing the knob',state('knob_scroll')==state('knob_rotary'),'EQ state unchanged')
sys.exit(bool(failures))
PY
eq reset_sound

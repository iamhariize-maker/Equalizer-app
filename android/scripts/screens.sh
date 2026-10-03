#!/usr/bin/env bash
# Screenshots of every Svan tab (for visual review). Usage: scripts/screens.sh [serial] [outdir]
set -u
S=${1:-emulator-5554}; OUT=${2:-${TMPDIR:-/tmp}/svan-screens}
A="adb -s $S"
mkdir -p "$OUT"
# Boot animation: cold start, capture mid-morph and at the full name.
$A shell am force-stop app.svan; $A shell am start -n app.svan/.MainActivity >/dev/null
sleep 1.2; $A exec-out screencap -p > "$OUT/boot-1.png"
sleep 0.9; $A exec-out screencap -p > "$OUT/boot-2.png"
echo "saved boot frames"
sleep 6
tap_text() { # taps the centre of the first node whose text equals $1
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$($A shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"')
  [ -z "$b" ] && { echo "no '$1' on screen"; return 1; }
  read -r x1 y1 x2 y2 < <(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  $A shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
shot() { sleep 4; $A exec-out screencap -p > "$OUT/$1.png"; echo "saved $OUT/$1.png"; }
shot 0-sound
# Swipe relative to the real screen size (CI's emulator is small).
read -r W H < <($A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
swipe_up() { $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 500; }
swipe_up; shot 0b-sound-tuners
swipe_up; shot 0c-sound-tuners
swipe_up; shot 0d-sound-tuners
swipe_up; shot 0e-sound-tuners
tap_text "EQ" && shot 1-eq
tap_text "Graphic" && shot 2-eq-graphic
tap_text "Parametric"
tap_text "Presets" && shot 3-presets
tap_text "Hi-Fi" && shot 4-hifi
tap_text "Lab" && shot 5-lab
tap_text "EQ"

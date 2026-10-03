#!/usr/bin/env bash
# Screenshots of every Svan tab (for visual review). Usage: scripts/screens.sh [serial] [outdir]
set -u
S=${1:-emulator-5554}; OUT=${2:-${TMPDIR:-/tmp}/svan-screens}
A="adb -s $S"
mkdir -p "$OUT"
$A shell am start -n app.svan/.MainActivity >/dev/null; sleep 8
tap_text() { # taps the centre of the first node whose text equals $1
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$($A shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"')
  [ -z "$b" ] && { echo "no '$1' on screen"; return 1; }
  read -r x1 y1 x2 y2 < <(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  $A shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
shot() { sleep 4; $A exec-out screencap -p > "$OUT/$1.png"; echo "saved $OUT/$1.png"; }
shot 0-sound
tap_text "EQ" && shot 1-eq
tap_text "Graphic" && shot 2-eq-graphic
tap_text "Parametric"
tap_text "Presets" && shot 3-presets
tap_text "Audiophile" && shot 4-audiophile
tap_text "Lab" && shot 5-lab
tap_text "EQ"

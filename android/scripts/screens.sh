#!/usr/bin/env bash
# Screenshots of every Svan tab (for visual review). Usage: scripts/screens.sh [serial] [outdir]
set -u
S=${1:-emulator-5554}; OUT=${2:-${TMPDIR:-/tmp}/svan-screens}
A="adb -s $S"
mkdir -p "$OUT"
# Capture Android's system splash before the Compose boot overlay.
$A shell am force-stop app.svan
$A shell am start -W --splashscreen-show-icon -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n app.svan/.MainActivity >/dev/null &
LAUNCH=$!
sleep 0.1
for frame in 1 2 3 4 5; do
  $A exec-out screencap -p > "$OUT/system-splash-$frame.png"
  sleep 0.1
done
wait "$LAUNCH"
sleep 3
# Boot animation: cold start, capture mid-morph and at the full name.
$A shell am force-stop app.svan; $A shell am start -n app.svan/.MainActivity >/dev/null
sleep 1.0; $A exec-out screencap -p > "$OUT/boot-1.png"
sleep 0.5; $A exec-out screencap -p > "$OUT/boot-2.png"
for frame in 3 4 5 6 7 8; do
  sleep 0.5
  $A exec-out screencap -p > "$OUT/boot-$frame.png"
done
echo "saved boot frames"
sleep 3
tap_text() { # taps the centre of the first node whose text equals $1
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$($A shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"')
  [ -z "$b" ] && { echo "no '$1' on screen"; return 1; }
  read -r x1 y1 x2 y2 < <(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  $A shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}
shot() { sleep 4; $A exec-out screencap -p > "$OUT/$1.png"; echo "saved $OUT/$1.png"; }
state() {
  $A logcat -c
  $A shell am start -n app.svan/.MainActivity --es cmd state >/dev/null
  local value="" t=0
  while [ "$t" -lt 10 ]; do
    value=$($A logcat -d -s EqSpike:I | sed -n 's/^.*EQ_STATE //p' | tail -1)
    [ -n "$value" ] && { echo "$value"; return 0; }
    sleep 1; t=$((t + 1))
  done
  return 1
}
BEFORE=$(state) || BEFORE=""
shot 0-sound
# Swipe relative to the real screen size (CI's emulator is small).
read -r W H < <($A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
swipe_up() { $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 5)) 500; }
swipe_up; shot 0b-sound-tuners
swipe_up; shot 0c-sound-tuners
swipe_up; shot 0d-sound-tuners
swipe_up; shot 0e-sound-tuners
swipe_up; shot 0f-orchestral-details
swipe_up; shot 0g-orchestral-guidance
AFTER=$(state) || AFTER=""
if [ -n "$BEFORE" ] && [ "$BEFORE" = "$AFTER" ]; then
  echo "PASS sound scroll preserves EQ settings" > "$OUT/interaction.txt"
else
  echo "FAIL sound scroll changed EQ settings" > "$OUT/interaction.txt"
fi
cat "$OUT/interaction.txt"
$A shell am start -n app.svan/.MainActivity --es cmd reset_sound >/dev/null
$A shell am start -n app.svan/.MainActivity --es cmd eq_control --ez auto true >/dev/null
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas --ez on true --es mode SVARESA --es night OFF --ez auto_headphone false >/dev/null
tap_text "EQ" && shot 1-eq
swipe_up; shot 1b-eq-live-bands
$A shell am start -n app.svan/.MainActivity --es cmd eq_control --ez auto true --ez graphic true --ei count 31 >/dev/null
shot 2-eq-graphic
swipe_up; shot 2b-eq-graphic-faders
$A shell am start -n app.svan/.MainActivity --es cmd eq_control --ez auto false >/dev/null
tap_text "Sound"; tap_text "EQ"; shot 2c-eq-manual
swipe_up; shot 2d-eq-manual-controls
tap_text "Presets" && shot 3-presets
tap_text "Hi-Fi" && shot 4-hifi
swipe_up; shot 4b-hifi-apps
swipe_up; shot 4c-hifi-quality
swipe_up; shot 4d-hifi-gain
swipe_up; shot 4e-hifi-protection
swipe_up; shot 4f-hifi-resolution
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas --ez on true --es mode SVARESA --es night OFF >/dev/null
$A shell input swipe $((W / 20)) $((H * 3 / 4)) $((W / 20)) $((H / 5)) 500
shot 4g-svaresa-protection
$A shell input swipe $((W / 20)) $((H * 3 / 4)) $((W / 20)) $((H / 5)) 500
shot 4h-selective-eq
tap_text "Lab" && shot 5-lab
tap_text "EQ"
# Svaramanas: the dialog over the app with a real request, scrolled through.
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas --ez on true --es mode GUIDED --es feel WARM --es picks VOCALS,STRINGS,BASS >/dev/null
sleep 2
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas_panel >/dev/null
shot 6-svaramanas
swipe_up; shot 6b-svaramanas
swipe_up; shot 6c-svaramanas
# The 3-4 rule: a 4th pick that clashes (Guitars vs Vocals) must be refused with a reason.
tap_text "Guitars" && shot 6d-svaramanas-clash
$A shell input keyevent KEYCODE_BACK
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas --ez on true --es mode SVARESA >/dev/null
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas_panel >/dev/null
shot 6e-svaresa
swipe_up; shot 6f-svaresa-details
$A shell input keyevent KEYCODE_BACK
$A shell am start -n app.svan/.MainActivity --es cmd svaramanas --ez on false >/dev/null

$A shell am start -n app.svan/.MainActivity --es cmd blind_lab >/dev/null
shot 7-blind-listening
$A shell input keyevent KEYCODE_BACK

# UI-only fixtures cover states that cannot be photographed live while adb is
# verifiably off. Every image is labelled; none starts capture or grants access.
for fixture in install debugging start authorize grant working error finish-on finish-off finish-unknown status-idle status-unreachable status-system status-audiophile status-unknown; do
  $A shell am start -n app.svan/.MainActivity --es cmd onboarding_fixture --es fixture "$fixture" >/dev/null
  sleep 2
  $A shell uiautomator dump /sdcard/svan-fixture.xml >/dev/null 2>&1
  $A shell cat /sdcard/svan-fixture.xml > "$OUT/8-setup-$fixture.xml"
  DENSITY=$($A shell wm density | grep -oE '[0-9]+' | tail -1)
  python3 scripts/assert_setup_screen.py "$OUT/8-setup-$fixture.xml" "$fixture" "$DENSITY" "$H"
  if [ "$?" != 0 ]; then echo "FAIL setup fixture $fixture" >> "$OUT/interaction.txt"; exit 1; fi
  echo "PASS setup fixture $fixture" >> "$OUT/interaction.txt"
  shot "8-setup-$fixture"
  swipe_up
  shot "8-setup-$fixture-detail"
  $A shell am start -n app.svan/.MainActivity --es cmd onboarding_close >/dev/null
  sleep 1
done

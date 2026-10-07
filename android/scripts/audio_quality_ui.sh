#!/usr/bin/env bash
# Layout and read-only JNI verification. Run after screens.sh on the preview, before production install.
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-audio-quality-ui}; A=(adb -s "$S")
mkdir -p "$OUT"
restore() {
  "${A[@]}" shell settings put system font_scale 1.0 >/dev/null
  "${A[@]}" shell wm size reset >/dev/null
  "${A[@]}" shell wm density reset >/dev/null
  "${A[@]}" logcat -d > "$OUT/logcat-full.txt" 2>&1 || true
}
trap restore EXIT
dump() {
  "${A[@]}" shell uiautomator dump /sdcard/svan-aq.xml >/dev/null 2>&1
  "${A[@]}" shell cat /sdcard/svan-aq.xml > "$1"
}
tap() {
  dump "$OUT/tap.xml"
  local point
  point=$(python3 scripts/ui_control.py "$OUT/tap.xml" "$1" || true)
  [[ -n "$point" ]] || { echo "FAIL missing tab/control: $1"; exit 1; }
  "${A[@]}" shell input tap $point
  sleep 1
}
"${A[@]}" logcat -c
"${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd audio_quality_lab >/dev/null
ready=''
for ((attempt=0;attempt<90;attempt++)); do
  ready=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/^.*AUDIO_QUALITY_LAB_READY //p' | tail -1)
  [[ -n "$ready" ]] && break
  if "${A[@]}" logcat -d -s EqSpike:I | grep -q AUDIO_QUALITY_LAB_FAILED; then "${A[@]}" logcat -d -s EqSpike:I; exit 1; fi
  sleep 1
done
[[ -n "$ready" ]] || { echo 'FAIL audio-quality JNI checks timed out'; exit 1; }
printf '%s\n' "$ready" > "$OUT/native-lab.json"
echo 'PASS Detailed creation, latency, aligned identity, diagnostic API and native rule JSON' > "$OUT/results.txt"
for width in 320 360 411; do
  for scale in 1.0 1.5; do
    dir="$OUT/${width}dp-font${scale}"
    mkdir -p "$dir"
    "${A[@]}" shell wm density 160 >/dev/null
    "${A[@]}" shell wm size "${width}x1200" >/dev/null
    "${A[@]}" shell settings put system font_scale "$scale" >/dev/null
    "${A[@]}" shell am force-stop app.svan
    "${A[@]}" shell am start -W -n app.svan/.MainActivity >/dev/null
    sleep 5
    tap 'Sound'
    : > "$dir/seen.txt"
    for ((position=0;position<18;position++)); do
      dump "$dir/sound-${position}.xml"
      python3 scripts/assert_audio_quality_ui.py "$dir/sound-${position}.xml" "$width" 1200 >> "$dir/seen.txt"
      "${A[@]}" exec-out screencap -p > "$dir/sound-${position}.png"
      if grep -qx 'Backing vocals' "$dir/seen.txt" && grep -qx 'Binaural' "$dir/seen.txt" && grep -qx 'Resolve' "$dir/seen.txt" && grep -qx 'Svaresa manages Resolve' "$dir/seen.txt" && grep -qx 'Space' "$dir/seen.txt" && grep -qx 'Instruments' "$dir/seen.txt"; then break; fi
      "${A[@]}" shell input swipe 8 900 8 520 350
      sleep 1
    done
    for label in 'Backing vocals' Binaural Resolve 'Svaresa manages Resolve' Space Instruments; do
      grep -qx "$label" "$dir/seen.txt" || { echo "FAIL $width dp font $scale missing $label"; exit 1; }
    done
    tap 'Hi-Fi'; tap 'How Svaresa decides'
    dump "$dir/rules.xml"
    grep -q 'SV-GAINPROT-1' "$dir/rules.xml"
    "${A[@]}" exec-out screencap -p > "$dir/rules.png"
    "${A[@]}" shell input keyevent KEYCODE_BACK
    echo "PASS audio-quality controls and rules at $width dp font $scale" >> "$OUT/results.txt"
  done
done
cat "$OUT/results.txt"

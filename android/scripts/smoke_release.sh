#!/usr/bin/env bash
# Installs the R8-minified release APK (what testers get), opens every tab and
# fails on any crash. Usage: scripts/smoke_release.sh [serial]
set -u
cd "$(dirname "$0")/.."
S=${1:-emulator-5554}; A="adb -s $S"
APK=app/build/outputs/apk/release/app-release.apk
$A uninstall app.svan >/dev/null 2>&1
$A install "$APK" || { echo "FAIL release install"; exit 1; }
$A shell pm grant app.svan android.permission.POST_NOTIFICATIONS 2>/dev/null
$A logcat -c
$A shell am start -W -n app.svan/.MainActivity >/dev/null; sleep 6
tap() {
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$($A shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"')
  [ -z "$b" ] && { echo "missing tab $1"; return 1; }
  read -r x1 y1 x2 y2 < <(echo "$b" | grep -oE '[0-9]+' | tr '\n' ' ')
  $A shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); sleep 3
}
ok=1
for t in EQ Presets Hi-Fi Lab Sound; do tap "$t" || ok=0; done
# Exercise native code paths that R8 could break (JNI, JSON, parsing).
$A shell am start -n app.svan/.MainActivity --es cmd preset >/dev/null; sleep 3
$A shell am start -n app.svan/.MainActivity --es cmd bass --es preset Punchy >/dev/null; sleep 3
if $A logcat -d | grep -qE "FATAL EXCEPTION|UnsatisfiedLinkError|NoSuchMethodError|ClassNotFoundException"; then
  echo "FAIL release build crashed:"; $A logcat -d | grep -A15 "FATAL EXCEPTION" | head -40; ok=0
fi
[ -n "$($A shell pidof app.svan)" ] || { echo "FAIL app.svan not running"; ok=0; }
$A logcat -d -s EqSpike:I | grep -E "preset bands|bass preset" | sed 's/^.*EqSpike: //'
[ $ok = 1 ] && echo "PASS release smoke test" || { echo "FAIL release smoke test"; exit 1; }

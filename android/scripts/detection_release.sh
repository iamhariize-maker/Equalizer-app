#!/usr/bin/env bash
# Real Shizuku authorization + shell reports without an app grant, then measured music routing.
set -uo pipefail   # no -e: keep going after a failed check so one run shows every problem
FAILED=0
cd "$(dirname "$0")/.."
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-detection}
mkdir -p "$OUT"
: > "$OUT/onboarding.txt"
A="adb -s $S"; CAP=app.svan.testsource.capturable
: > "$OUT/detection.txt"
$A logcat -c
$A logcat -v threadtime > "$OUT/logcat-full.txt" 2>&1 &   # whole run, never cleared
LOGPID=$!
trap 'kill $LOGPID 2>/dev/null; $A logcat -d > "$OUT/logcat.txt"; exit $FAILED' EXIT
eq() { $A shell am start -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
tone() { $A shell am start -n "$CAP"/app.svan.testsource.ToneActivity "$@" >/dev/null; }
wait_log() {
  for ((i=0;i<60;i++)); do
    $A logcat -d -s EqSpike:I | grep -E "$1" | tail -1 > "$OUT/match.txt" || true
    if [ -s "$OUT/match.txt" ]; then cat "$OUT/match.txt"; return 0; fi
    sleep 1
  done
  echo "FAIL timeout: $1" | tee -a "$OUT/detection.txt"
  FAILED=1
  return 1
}
tap() {
  $A shell uiautomator dump /sdcard/svan-setup.xml >/dev/null 2>&1 || return 1
  $A shell cat /sdcard/svan-setup.xml > "$OUT/ui.xml"
  local point
  point=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
    if n.get('text') == sys.argv[2]:
        a=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
        if len(a)==4: print((a[0]+a[2])//2,(a[1]+a[3])//2);sys.exit(0)
sys.exit(1)
PY
  ) || return 1
  $A shell input tap $point
}
level() {
  $A logcat -c; eq measure_mix --ef seconds 3
  wait_log 'MIX median=' | sed -n 's/.*median=\([-0-9.]*\).*/\1/p'
}
diagnose() {   # state at the moment a check fails
  local tag; tag=$(echo "$1" | tr -c 'A-Za-z0-9' '_' | cut -c1-40)
  { echo "=== $1"; $A shell dumpsys audio 2>/dev/null | grep -E "AudioPlaybackConfiguration|muted|sessionId" | head -20
    echo "--- effects"; $A shell dumpsys media.audio_flinger 2>/dev/null | grep -iE "effect|Dynamics|session|enabled" | head -40
    echo "--- svan processes"; $A shell "ps -A | grep -E 'svan|shizuku'"; } > "$OUT/diag-$tag.txt" 2>&1
  eq sessions; sleep 2
  $A logcat -d -s EqSpike:I > "$OUT/eqspike-$tag.txt"
  $A exec-out screencap -p > "$OUT/fail-$tag.png"
}
check_delta() {
  python3 - "$1" "$2" "$3" "$4" <<'PY' | tee -a "$OUT/detection.txt"
import sys
name,a,b,delta=sys.argv[1:];a,b,delta=map(float,(a,b,delta))
ok=abs((a-b)-delta)<=1.0
print(('PASS ' if ok else 'FAIL ')+name+f' — measured change {a-b:+.1f} dB, expected {delta:+.1f} ±1 dB')
sys.exit(0 if ok else 1)
PY
  local rc=${PIPESTATUS[0]}
  if [ "$rc" != 0 ]; then FAILED=1; diagnose "$1"; fi
}

$A uninstall app.svan >/dev/null 2>&1 || true
$A install app/build/outputs/apk/release/app-release.apk >/dev/null
$A install -r testsource/build/outputs/apk/capturable/debug/testsource-capturable-debug.apk >/dev/null
$A shell pm grant app.svan android.permission.RECORD_AUDIO
$A shell pm grant app.svan android.permission.POST_NOTIFICATIONS
$A shell pm revoke app.svan android.permission.DUMP
$A shell appops set app.svan PROJECT_MEDIA allow
$A shell cmd media_session volume --stream 3 --set 4 >/dev/null
$A logcat -c
eq reset_sound; eq preset; sleep 6
tone --ef freq 1000 --ef amp 0.25 --ez broadcast false; sleep 3
BASE=$(level)
tap 'Hi-Fi'; sleep 2
$A exec-out screencap -p > "$OUT/setup-required.png"
if tap "Fix music detection"; then
  sleep 2; $A exec-out screencap -p > "$OUT/wizard-install.png"
  echo "PASS live contextual prompt opens wizard" >> "$OUT/onboarding.txt"
else echo "FAIL live contextual prompt missing" >> "$OUT/onboarding.txt"; FAILED=1; fi

# Pinned official manager APK; starter runs as shell (uid 2000), not root.
$A install -r -g "${SHIZUKU_APK:?set SHIZUKU_APK to the pinned official APK}" >/dev/null
$A shell monkey -p moe.shizuku.privileged.api -c android.intent.category.LAUNCHER 1 >/dev/null
unzip -p "$SHIZUKU_APK" lib/x86_64/libshizuku.so > "$OUT/shizuku-starter"
$A push "$OUT/shizuku-starter" /data/local/tmp/svan-shizuku-starter >/dev/null
$A shell chmod 755 /data/local/tmp/svan-shizuku-starter
MANAGER=$($A shell pm path moe.shizuku.privileged.api | head -1 | sed 's/package://' | tr -d '\r')
$A shell /data/local/tmp/svan-shizuku-starter --apk="$MANAGER" > "$OUT/shizuku-start.txt" 2>&1
sleep 4
$A logcat -c; eq onboarding_state; sleep 3
$A exec-out screencap -p > "$OUT/wizard-authorization.png"
read -r W H < <($A shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
wizard_grant() {
  for ((attempt=0;attempt<6;attempt++)); do
    if tap 'Allow Svan' || tap 'Enable music detection'; then return 0; fi
    $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500; sleep 1
  done
  return 1
}
if wizard_grant; then
  echo 'PASS wizard connects shell reports from its real button' >> "$OUT/onboarding.txt"
else echo 'FAIL wizard grant button missing' >> "$OUT/onboarding.txt"; FAILED=1; fi
sleep 2
for ((attempt=0;attempt<12;attempt++)); do
  if tap 'Allow all the time'; then break; fi
  sleep 1
done
if wait_log 'detection setup: shell audio reports ready'; then
  $A shell dumpsys package app.svan > "$OUT/package-after-setup.txt"
  if grep -q 'android.permission.DUMP: granted=true' "$OUT/package-after-setup.txt"; then
    echo 'FAIL shell setup unexpectedly granted app DUMP' | tee -a "$OUT/detection.txt"; FAILED=1
  else echo 'PASS release shell reports work without app DUMP grant' | tee -a "$OUT/detection.txt"; fi
else FAILED=1; fi
wait_log "route: $CAP .*Engine A"
sleep 3
AFTER=$(level)
# Return to the top to verify the enabled heading on small screens.
$A shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1
$A shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 500; sleep 1
check_delta 'already-playing app becomes processed after setup' "$AFTER" "$BASE" -6.3
$A exec-out screencap -p > "$OUT/wizard-finish.png"
# Finish is allowed even when debugging remains on; the warning must remain honest.
$A shell uiautomator dump /sdcard/svan-setup.xml >/dev/null 2>&1
$A shell cat /sdcard/svan-setup.xml > "$OUT/debugging-ui.xml"
if grep -q 'Music detection enabled' "$OUT/debugging-ui.xml"; then
  echo 'PASS wizard observes validated shell report access' >> "$OUT/onboarding.txt"
else echo 'FAIL wizard did not show enhanced detection success' >> "$OUT/onboarding.txt"; FAILED=1; fi
# Scroll the finish card into view, then compare USB text with the actual setting.
for ((attempt=0;attempt<6;attempt++)); do
  if grep -q 'Developer options:' "$OUT/debugging-ui.xml"; then break; fi
  $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500; sleep 1
  $A shell uiautomator dump /sdcard/svan-setup.xml >/dev/null 2>&1
  $A shell cat /sdcard/svan-setup.xml > "$OUT/debugging-ui.xml"
done
USB=$($A shell settings get global adb_enabled | tr -d '\r\n')
case "$USB" in 0) USB_LABEL=Off ;; 1) USB_LABEL=On ;; *) USB_LABEL="Can't tell" ;; esac
if grep -Fq "USB: $USB_LABEL" "$OUT/debugging-ui.xml"; then
  echo 'PASS wizard debugging display matches actual USB setting' >> "$OUT/onboarding.txt"
else echo 'FAIL wizard debugging display does not match USB setting' >> "$OUT/onboarding.txt"; FAILED=1; fi
$A exec-out screencap -p > "$OUT/wizard-debugging-live.png"
for ((attempt=0;attempt<8;attempt++)); do
  if tap 'Finish'; then break; fi
  $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500; sleep 1
done
if tap 'Hi-Fi'; then echo 'PASS wizard Finish returns to player status' >> "$OUT/onboarding.txt"
else echo 'FAIL wizard Finish did not close' >> "$OUT/onboarding.txt"; FAILED=1; fi
sleep 2
$A exec-out screencap -p > "$OUT/setup-enabled.png"

# Stop the helper and prove the no-Shizuku session-broadcast path still processes.
$A shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
tone --ez stop true; sleep 3
$A logcat -c
tone --ef freq 1000 --ef amp 0.25 --ez broadcast true
wait_log "route: $CAP .*Engine A"; sleep 3
INDEPENDENT=$(level)
check_delta 'broadcast detection works after Shizuku stops' "$INDEPENDENT" "$BASE" -6.3
# Restore shell reports for absence reconciliation and the following capture quality checks.
$A shell /data/local/tmp/svan-shizuku-starter --apk="$MANAGER" > "$OUT/shizuku-restart.txt" 2>&1
wait_log 'detection setup: shell audio reports ready'

# The prior non-broadcast AudioTrack is retired after successful discovery scans.
# Do not race that existing grace period when measuring a single-source capture.
# Multi-session startup's intermediate UID conflict remains a product limitation;
# this setup wait does not change routing, grant access, or relax audio assertions.
settled=0
for ((attempt=0;attempt<20;attempt++)); do
  $A logcat -c; eq sessions; sleep 2
  $A logcat -d -s EqSpike:I > "$OUT/pre-capture-routes.txt"
  if python3 - "$OUT/pre-capture-routes.txt" "$CAP" <<'PY'
import re,sys
rows=re.findall(r'routes: (.*)',open(sys.argv[1]).read())
entries=re.findall(re.escape(sys.argv[2])+r'#[0-9]+=([A-Z_]+)',rows[-1]) if rows else []
sys.exit(0 if entries==['ENGINE_A'] else 1)
PY
  then settled=1; break; fi
done
if [ "$settled" != 1 ]; then
  echo 'FAIL test source retains multiple or missing routes before single-source capture' | tee -a "$OUT/detection.txt"
  FAILED=1; diagnose 'test source retirement'; exit 1
fi

# Test the shipped 4x path and the same graphic controls shown in the phone report.
$A logcat -c; eq start_capture --es quality AUDIOPHILE
wait_log 'capture: started'; wait_log "route: $CAP .*Engine B"; sleep 3
# Do not measure the release output as an Engine B result unless the expected
# route log exists. Engine A can produce the same level delta and mask a miss.
if ! grep -Eq "route: $CAP .*Engine B" < <($A logcat -d -s EqSpike:I); then
  echo 'FAIL release Engine B route missing; capture level checks are invalid' | tee -a "$OUT/detection.txt"
  FAILED=1
  diagnose 'release Engine B route missing'
  eq stop_capture
  exit 1
fi
CAPTURED=$(level)
check_delta 'release audiophile output is one processed copy' "$CAPTURED" "$BASE" -6.3
eq gain_settings --ez headroom false --ez protection true
eq graphic_test; sleep 4
BOOST=$(level)
check_delta 'release graphic EQ produces +6 dB at 1 kHz' "$BOOST" "$BASE" 6
eq bypass --ez off true; sleep 3
BYPASS=$(level)
check_delta 'release audiophile bypass restores original level' "$BYPASS" "$BASE" 0
eq bypass --ez off false; eq eq_band --ef frequency 1000 --ef gain -12; sleep 3
CUT=$(level)
check_delta 'release parametric EQ produces a 12 dB cut' "$CUT" "$BASE" -12
tap 'Hi-Fi'; sleep 2
peaks_visible=0
for ((attempt=0;attempt<10;attempt++)); do
  timeout 15s $A shell uiautomator dump /sdcard/svan-peaks.xml >/dev/null 2>&1 || continue
  $A shell cat /sdcard/svan-peaks.xml > "$OUT/status-audiophile-live.xml"
  if grep -Fq 'Capture peaks' "$OUT/status-audiophile-live.xml"; then peaks_visible=1; break; fi
  sleep 1
done
if [ "$peaks_visible" == 1 ]; then
  echo 'PASS routed Audiophile status displays existing live capture peaks' >> "$OUT/onboarding.txt"
else
  echo 'FAIL routed Audiophile status missing existing live capture peaks' >> "$OUT/onboarding.txt"
  FAILED=1
fi
$A exec-out screencap -p > "$OUT/status-audiophile-live.png"
# Connected status and signal readings are below the detection card.
$A shell input swipe 160 500 160 140 500; sleep 2
$A exec-out screencap -p > "$OUT/connected-audiophile.png"
# A full reset must remove hidden tuning layers as well as the manual EQ.
eq bass --es preset Punchy
eq tuners --ef warmth 1 --ef smooth 1 --ef space 1
sleep 3
eq reset_sound; sleep 3
RESET=$(level)
check_delta 'reset all sound restores capture level after layered effects' "$RESET" "$BASE" 0

# Exercise bass resolution on the actual system-effect output, beyond 1 kHz.
eq stop_capture; sleep 3
tone --ez stop true; sleep 2
tone --ef freq 63 --ef amp 0.1 --ez broadcast true; sleep 4
LOWBASE=$(level)
eq eq_band --ef frequency 63 --ef gain 6; sleep 3
LOWBOOST=$(level)
check_delta 'system EQ produces a 6 dB bass boost at 63 Hz' "$LOWBOOST" "$LOWBASE" 6
eq reset_sound; sleep 3
eq eq_band --ef frequency 63 --ef gain -6; sleep 3
LOWCUT=$(level)
check_delta 'system EQ produces a 6 dB bass cut at 63 Hz' "$LOWCUT" "$LOWBASE" -6
eq reset_sound

# Some ROMs (HiOS) never let Shizuku's helper process answer. Skip it and read the same
# reports through Shizuku's own shell process: a hidden (non-broadcast) player must still be
# discovered from those reports and processed by the same curve.
tone --ez stop true; sleep 3
eq preset; sleep 3
$A logcat -c
eq shell_route --ez direct true
if wait_log 'detection setup: shell audio reports ready \(direct Shizuku route'; then
  tone --ef freq 1000 --ef amp 0.25 --ez broadcast false
  wait_log "route: $CAP .*Engine A"; sleep 3
  DIRECT=$(level)
  check_delta 'hidden player is processed through the direct Shizuku route' "$DIRECT" "$BASE" -6.3
else FAILED=1; diagnose 'direct Shizuku route'; fi
eq reset_sound

# Payment apps refuse to run beside Shizuku. One tap on the real button must move enhanced
# detection into Svan, and a hidden player must still be processed after Shizuku is uninstalled.
tone --ez stop true; sleep 3
eq preset; sleep 3
tap 'Hi-Fi'; sleep 2
$A logcat -c
kept=0
for ((attempt=0;attempt<10;attempt++)); do
  if tap 'Keep enhanced detection without Shizuku'; then kept=1; break; fi
  $A shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 500; sleep 1
done
if [ "$kept" == 1 ] && wait_log 'dump grant: granted'; then
  $A shell dumpsys package app.svan > "$OUT/package-after-keep.txt"
  if grep -q 'android.permission.DUMP: granted=true' "$OUT/package-after-keep.txt"; then
    echo 'PASS one tap keeps enhanced detection inside Svan' | tee -a "$OUT/detection.txt"
  else echo 'FAIL keep button reported success but app DUMP is not granted' | tee -a "$OUT/detection.txt"; FAILED=1; fi
else
  echo 'FAIL keep-enhanced button missing or its grant failed' | tee -a "$OUT/detection.txt"
  FAILED=1; diagnose 'keep enhanced without Shizuku'
fi
sleep 2; $A exec-out screencap -p > "$OUT/keep-enhanced.png"
$A shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
$A uninstall moe.shizuku.privileged.api >/dev/null
sleep 3
$A logcat -c
tone --ef freq 1000 --ef amp 0.25 --ez broadcast false
if wait_log "route: $CAP .*Engine A"; then
  sleep 3
  KEPT=$(level)
  check_delta 'hidden player is processed after Shizuku is uninstalled' "$KEPT" "$BASE" -6.3
else diagnose 'hidden player after Shizuku uninstall'; fi
eq reset_sound
tone --ez stop true
$A uninstall app.svan >/dev/null

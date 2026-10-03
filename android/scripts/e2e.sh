#!/usr/bin/env bash
# End-to-end test of Engine A / Engine B routing on an emulator or device.
# Plays known tones from the test-source apps and measures the device's final
# output mix (Visualizer on session 0) to tell apart:
#   one processed copy  vs  double audio (louder)  vs  silence (muted, not re-rendered)
# Usage:
#   From the repo:            scripts/e2e.sh [adb-serial]
#   On the phone (Termux):    APK_DIR=~/storage/downloads bash e2e.sh 127.0.0.1:<port>
#     APK_DIR must contain svan.apk, svan-testsource-capturable.apk, svan-testsource-blocked.apk
# The test plays a 1 kHz tone; VOLUME (0-15, default 4) sets the media volume.
set -u
S=${1:-emulator-5554}
TMP=${TMPDIR:-/tmp}
if [ -n "${APK_DIR:-}" ]; then
  APP_APK=$APK_DIR/svan.apk
  CAP_APK=$APK_DIR/svan-testsource-capturable.apk
  BLK_APK=$APK_DIR/svan-testsource-blocked.apk
else
  cd "$(dirname "$0")/.."
  APP_APK=app/build/outputs/apk/debug/app-debug.apk
  CAP_APK=testsource/build/outputs/apk/capturable/debug/testsource-capturable-debug.apk
  BLK_APK=testsource/build/outputs/apk/blocked/debug/testsource-blocked-debug.apk
fi
A="adb -s $S"
EQ=app.svan
CAP=app.svan.testsource.capturable
BLK=app.svan.testsource.blocked
QUALITY=${QUALITY:-EFFICIENT}

log() { echo "[$(date +%H:%M:%S)] $*"; }
eq() { $A shell am start -n $EQ/.MainActivity --es cmd "$@" >/dev/null; }
tone() { $A shell am start -n "$1"/app.svan.testsource.ToneActivity "${@:2}" >/dev/null; }
# Wait for a logcat line matching $1 (tag EqSpike), print it.
wait_for() {
  local pat=$1 timeout=${2:-120} t=0
  while [ $t -lt "$timeout" ]; do
    line=$($A logcat -d -s EqSpike:I | grep -E "$pat" | tail -1)
    [ -n "$line" ] && { echo "$line" | sed 's/.*EqSpike: //'; return 0; }
    sleep 2; t=$((t + 2))
  done
  echo "TIMEOUT waiting for: $pat"; return 1
}
measure() { # $1 = label
  $A logcat -c
  eq measure_mix --ef seconds 4
  local r; r=$(wait_for "MIX " 90)
  log "$1: $r"
  echo "$1|$r" >> "$TMP/e2e_results.txt"
}

: > "$TMP/e2e_results.txt"
$A logcat -s EqSpike:I EqTestSource:I > "$TMP/e2e_eqspike_full.log" 2>/dev/null &
FULLLOG=$!
log "installing"
for apk in "$APP_APK" "$CAP_APK" "$BLK_APK"; do
  [ -f "$apk" ] || { echo "missing $apk"; exit 1; }
  $A install -r -g "$apk" >/dev/null || { echo "install failed: $apk"; exit 1; }
done
$A shell pm grant $EQ android.permission.DUMP
$A shell pm grant $EQ android.permission.RECORD_AUDIO
$A shell pm grant $EQ android.permission.POST_NOTIFICATIONS 2>/dev/null
$A shell appops set $EQ PROJECT_MEDIA allow
$A shell cmd media_session volume --stream 3 --set "${VOLUME:-4}" >/dev/null 2>&1 \
  || $A shell media volume --stream 3 --set "${VOLUME:-4}" >/dev/null 2>&1 || true

log "T0 baseline: capturable tone, EQ flat"
eq forget_verdicts; sleep 3
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast true; sleep 6
measure "T0 one unprocessed copy (Engine A flat)"

log "T1 Engine A: load preset"
$A logcat -c; eq preset; wait_for "preset bands" 60
sleep 4
measure "T1 Engine A preset (expect T0 + curve@1k)"

log "T2 Engine B: start capture ($QUALITY)"
$A logcat -c; eq start_capture --es quality $QUALITY
wait_for "capture: started" 120
wait_for "route: $CAP .*Engine B|capture check: $CAP" 120
sleep 5
measure "T2 Engine B single processed copy (expect ~T1; double audio would be louder)"
eq sessions; wait_for "routes:" 60

log "T3 blocked app (capture opt-out)"
tone $CAP --ez stop true; sleep 2
$A logcat -c
tone $BLK --ef freq 1000 --ef amp 0.25 --ez broadcast true
wait_for "capture check: $BLK|route: $BLK" 120
sleep 5
measure "T3 blocked app on Engine A (expect ~T1, not silence)"
tone $BLK --ez stop true; sleep 2

log "T4 discovery without broadcast (DUMP path)"
eq forget_verdicts; sleep 2; $A logcat -c
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false
wait_for "route: $CAP .*Engine B|capture check: $CAP" 120
sleep 5
measure "T4 non-broadcasting app via DUMP (expect ~T1)"

log "T5 stop capture: everything back to Engine A"
$A logcat -c; eq stop_capture; wait_for "capture: stopped" 60; sleep 5
measure "T5 after stop (expect ~T1 via Engine A, or T0 if discovered only by dump)"
tone $CAP --ez stop true

# ---- verdicts -------------------------------------------------------------
FULL="$TMP/e2e_eqspike_full.log"
lvl() { grep "^$1 " "$TMP/e2e_results.txt" | grep -oE 'median=-?[0-9.]+' | cut -d= -f2; }
RESP=$(grep -oE 'response@1kHz=-?[0-9.]+' "$FULL" | tail -1 | cut -d= -f2)
T0=$(lvl T0); T1=$(lvl T1); T2=$(lvl T2); T3=$(lvl T3); T4=$(lvl T4)
check() { # name ok? detail
  if [ "$2" = 1 ]; then echo "PASS $1 — $3"; else echo "FAIL $1 — $3"; fi >> "$TMP/e2e_results.txt"
}
near() { awk -v a="$1" -v b="$2" -v t="$3" 'BEGIN { d = a - b; if (d < 0) d = -d; print (a != "" && b != "" && d <= t) ? 1 : 0 }'; }
EXP1=$(awk -v a="$T0" -v r="$RESP" 'BEGIN { print a + r }')
check "Engine A applies the curve" "$(near "$T1" "$EXP1" 1.0)" "T1=$T1, expected T0+($RESP)=$EXP1 ±1 dB"
B_ROUTE=$(grep -cE "route: $CAP .*Engine B" "$FULL")
check "Engine B takes the capturable app" "$([ "$B_ROUTE" -ge 1 ] && echo 1 || echo 0)" "Engine B routes for $CAP: $B_ROUTE"
check "Engine B: one processed copy, no double audio" "$(near "$T2" "$T1" 2.0)" "T2=$T2 vs T1=$T1 ±2 dB (double audio ≥ T0=$T0)"
BLK_B=$(grep -cE "route: $BLK .*Engine B" "$FULL"); BLK_A=$(grep -cE "route: $BLK .*Engine A" "$FULL")
check "Capture-blocked app stays on Engine A" "$([ "$BLK_B" -eq 0 ] && [ "$BLK_A" -ge 1 ] && echo 1 || echo 0)" "A=$BLK_A B=$BLK_B"
check "Blocked app still audible with EQ" "$(near "$T3" "$T1" 2.0)" "T3=$T3 vs T1=$T1 ±2 dB"
check "Non-broadcasting app found via DUMP" "$(near "$T4" "$T1" 2.0)" "T4=$T4 vs T1=$T1 ±2 dB"
log "results:"; cat "$TMP/e2e_results.txt"
$A logcat -d -s EqSpike:I > "$TMP/e2e_eqspike.log"
kill $FULLLOG 2>/dev/null
log "full app log: $TMP/e2e_eqspike.log"
if [ -n "${APK_DIR:-}" ]; then
  cp "$TMP/e2e_results.txt" "$TMP/e2e_eqspike.log" "$APK_DIR/" && log "copied results to $APK_DIR"
fi

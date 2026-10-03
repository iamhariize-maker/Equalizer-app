#!/usr/bin/env bash
# End-to-end test of Engine A / Engine B routing on an emulator or device.
# Plays known tones from the test-source apps and measures the device's final
# output mix (Visualizer on session 0) to tell apart:
#   one processed copy  vs  double audio (louder)  vs  silence (muted, not re-rendered)
# Usage: scripts/e2e.sh [adb-serial]
set -u
cd "$(dirname "$0")/.."
S=${1:-emulator-5554}
A="adb -s $S"
EQ=dev.equalizer.app
CAP=dev.equalizer.testsource.capturable
BLK=dev.equalizer.testsource.blocked
QUALITY=${QUALITY:-EFFICIENT}

log() { echo "[$(date +%H:%M:%S)] $*"; }
eq() { $A shell am start -n $EQ/.MainActivity --es cmd "$@" >/dev/null; }
tone() { $A shell am start -n "$1"/dev.equalizer.testsource.ToneActivity "${@:2}" >/dev/null; }
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
  echo "$1|$r" >> /tmp/e2e_results.txt
}

: > /tmp/e2e_results.txt
log "installing"
$A install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A install -r testsource/build/outputs/apk/capturable/debug/testsource-capturable-debug.apk >/dev/null
$A install -r testsource/build/outputs/apk/blocked/debug/testsource-blocked-debug.apk >/dev/null
$A shell pm grant $EQ android.permission.DUMP
$A shell pm grant $EQ android.permission.RECORD_AUDIO
$A shell pm grant $EQ android.permission.POST_NOTIFICATIONS 2>/dev/null
$A shell appops set $EQ PROJECT_MEDIA allow
$A shell media volume --stream 3 --set 15 >/dev/null 2>&1 || true

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

log "results:"; cat /tmp/e2e_results.txt
$A logcat -d -s EqSpike:I > /tmp/e2e_eqspike.log

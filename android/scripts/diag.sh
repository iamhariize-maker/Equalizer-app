#!/usr/bin/env bash
# Engine B diagnostics: does a UID's playback capture carry audio before and
# after its session is muted? Does the audio-service dump list sessions?
# Usage: scripts/diag.sh [serial] [outdir]
set -u
cd "$(dirname "$0")/.."
S=${1:-emulator-5554}; OUT=${2:-${TMPDIR:-/tmp}/svan-diag}
A="adb -s $S"
EQ=app.svan
CAP=app.svan.testsource.capturable
mkdir -p "$OUT"
eq() { $A shell am start -n $EQ/.MainActivity --es cmd "$@" >/dev/null 2>&1; }
tone() { $A shell am start -n "$1"/app.svan.testsource.ToneActivity "${@:2}" >/dev/null 2>&1; }
wait_for() { # pattern timeout — searches the full log file
  local t=0; while [ $t -lt "${2:-90}" ]; do grep -qE "$1" "$OUT/eqspike.log" && return 0; sleep 2; t=$((t + 2)); done
  echo "TIMEOUT: $1"; return 1
}

$A install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
$A install -r testsource/build/outputs/apk/capturable/debug/testsource-capturable-debug.apk >/dev/null
$A shell pm grant $EQ android.permission.DUMP
$A shell appops set $EQ PROJECT_MEDIA allow
$A logcat -c
$A logcat -s EqSpike:I EqTestSource:I > "$OUT/eqspike.log" &
LOGPID=$!

echo "== dump before anything plays"
eq dump_lines; wait_for "DUMP size" 30
echo "== tone + start capture"
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast true; sleep 5
eq dump_lines; sleep 4
eq start_capture --es quality EFFICIENT; wait_for "capture: started" 60
wait_for "capture check: $CAP|route: $CAP" 60; sleep 6
echo "== capture unmuted vs muted"
eq diag_capture --es pkg $CAP; wait_for "DIAG done" 60
echo "== non-broadcasting session via dump"
tone $CAP --ez stop true; sleep 3
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false; sleep 10
eq sessions; wait_for "routes:" 30; sleep 2
eq dump_lines; sleep 6
eq stop_capture; sleep 3
kill $LOGPID 2>/dev/null
$A logcat -d > "$OUT/logcat-all.txt"
echo "== EqSpike log"; sed 's/^.*EqSpike: //; s/^.*EqTestSource: /[src] /' "$OUT/eqspike.log"

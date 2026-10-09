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
eq() { $A shell am start -n $EQ/.Command --es cmd "$@" >/dev/null; }
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
# Poll the app's state file until a python expression over it holds; prints 1 or 0.
mix_state() { # $1 = expression over s
  for i in $(seq 1 15); do
    eq onboarding_state; sleep 2
    $A shell run-as $EQ cat files/onboarding-state.json > "$TMP/e2e_state.json" 2>/dev/null || continue
    if python3 -c "import json,sys; s=json.load(open(sys.argv[1])); sys.exit(0 if ($1) else 1)" "$TMP/e2e_state.json"; then echo 1; return; fi
  done
  echo 0
}
measure() { # $1 = label
  $A logcat -c
  eq measure_mix --ef seconds 4
  local r; r=$(wait_for "MIX " 90)
  log "$1: $r"
  echo "$1|$r" >> "$TMP/e2e_results.txt"
}

# Separate UX prelude; the 39 original measured audio checks below remain unchanged.
if [ -z "${APK_DIR:-}" ]; then
  bash scripts/onboarding_e2e.sh "$S" "${SVAN_ONBOARDING_OUT:-$TMP/onboarding-e2e}" "$APP_APK" || exit 1
fi

: > "$TMP/e2e_results.txt"
$A logcat -s EqSpike:I EqTestSource:I > "$TMP/e2e_eqspike_full.log" 2>/dev/null &
FULLLOG=$!
log "installing"
# Start from a clean install: saved EQ state from an earlier run would skew T0.
$A uninstall app.svan >/dev/null 2>&1
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
eq forget_verdicts; eq reset_sound; sleep 3
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
log "T2b orchestral amplifier must not touch a centred source"
eq tuners --ef space 1 --ef instruments 1; wait_for "tuners vocal" 30; sleep 4
measure "T2b Engine B with space+instruments at max (expect = T2: centre untouched)"
eq tuners; sleep 2

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

log "T6 bass tuner on system effects"
$A logcat -c; eq bass --es preset Punchy; wait_for "bass preset=Punchy" 30; sleep 5
measure "T6 bass tuner Punchy (expect T0 + response@1k)"

log "T7 headphone tuning from AutoEq (network)"
$A logcat -c; eq tune --es query "'Sennheiser HD 650'" --es source oratory1990; wait_for "tune: " 120; sleep 5
measure "T7 HD 650 -> Harman tuning (expect T0 + response@1k)"
eq bass --es preset Off

log "T8 vocal tuner on system effects"
$A logcat -c; eq tuners --ef intimacy 1 --ef warmth 1 --ef smooth 1; wait_for "tuners vocal" 30; sleep 5
measure "T8 vocal tuner max on system effects (expect T0 + response@1k)"
eq tuners; sleep 3
tone $CAP --ez stop true

log "T9 explicit EQ boost with headroom disabled"
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast true; sleep 3
eq eq_band --ef frequency 1000 --ef gain 6
eq gain_settings --ez headroom false --ez protection true; sleep 5
measure "T9 +6 dB bell without predictive headroom (expect T0 + 6)"
eq bypass --ez off true; sleep 4
measure "T10 bypass restores flat level (expect T0)"
eq bypass --ez off false
eq gain_settings --ez headroom true --ez protection true
eq preset; sleep 4

log "T11 capturable app forced to system effects during capture"
eq app_engine --es pkg $CAP --ez system_only true
$A logcat -c; eq start_capture --es quality EFFICIENT; wait_for "capture: started" 120; sleep 6
measure "T11 system override excludes capturable audio (expect T1, no double copy)"
$A logcat -c; eq app_engine --es pkg $CAP --ez system_only false
wait_for "capture: stopped" 60; sleep 4
measure "T12 changing override stops capture safely (expect T1)"
$A logcat -c; eq start_capture --es quality EFFICIENT; wait_for "capture: started" 120
wait_for "route: $CAP .*Engine B" 120; sleep 5
measure "T13 auto restored to Engine B (expect T1)"
$A logcat -c; eq engine_mode --ez system_only true
wait_for "capture: stopped" 60; sleep 4
measure "T14 global system-only stops capture (expect T1)"

log "T15 Engine A while activity is backgrounded"
$A logcat -c; eq measure_mix --ef seconds 6
$A shell input keyevent KEYCODE_HOME
r=$(wait_for "MIX " 90)
echo "T15 system EQ in background|$r" >> "$TMP/e2e_results.txt"

log "T16 undetected app must not enter capture mix"
tone $CAP --ez stop true; sleep 3
eq stop_system; sleep 3
$A shell pm revoke $EQ android.permission.DUMP
eq start_system; sleep 3
eq engine_mode --ez system_only false
$A logcat -c; eq start_capture --es quality EFFICIENT; wait_for "capture: start blocked" 30 > "$TMP/e2e_t16_blocked.txt"
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false; sleep 6
measure "T16 unknown non-broadcasting audio stays single and unprocessed (expect T0)"
eq stop_capture; sleep 3
tone $CAP --ez stop true; sleep 3

log "T17 general player broadcasts without DUMP"
$A logcat -c
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez explicit false
wait_for "route: $CAP .*Engine A" 30; sleep 3
measure "T17 implicit broadcast connects without enhanced permission"
tone $CAP --ez stop true; sleep 3

log "T18 already-playing player before a live detection grant"
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false; sleep 3
measure "T18 no detection before permission grant"
# The test meter is first in the output-mix chain, so the whole-mix fallback is verified structurally.
MIX_ON=$(mix_state "s['mixFallback'] and not s['capture']")
$A shell dumpsys media.audio_flinger > "$TMP/e2e_mix_af.txt"
$A logcat -c
$A shell pm grant $EQ android.permission.DUMP
eq refresh_detection
wait_for "route: $CAP .*Engine A" 30; sleep 3
measure "T19 live permission grant discovers existing playback"
MIX_OFF=$(mix_state "not s['mixFallback'] and s['connectedPlayers']>=1")
log "T21 Svaramanas static plan on system effects"
$A logcat -c
eq svaramanas --ez on true --es mode GUIDED --es feel BRIGHT --es picks VOCALS,GUITARS,DRUMS
wait_for "svaramanas plan" 30 > "$TMP/e2e_t21_plan.txt"; cat "$TMP/e2e_t21_plan.txt"
sleep 4
measure "T21 Svaramanas plan on system effects (expect T0 + response@1k)"
eq svaramanas --ez on false; sleep 3
tone $CAP --ez stop true; sleep 3
log "T20 muted source whose capture goes silent mid-playback must fail open to Engine A"
eq engine_mode --ez system_only false
$A logcat -c; eq start_capture --es quality EFFICIENT; wait_for "capture: started" 120
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast true; sleep 8
log "T22 Svaramanas hears the captured source"
eq svaramanas --ez on true --es mode GUIDED --es feel BALANCED --es picks VOCALS
wait_for "svaramanas heard: valid=true" 40 > "$TMP/e2e_t22_heard.txt"; cat "$TMP/e2e_t22_heard.txt"
eq svaramanas --ez on false; sleep 3
log "T23 Svaresa automatic master uses the native plan"
$A logcat -c
eq svaramanas --ez on true --es mode SVARESA --es feel BRIGHT --es picks VOCALS
wait_for "svaramanas plan: mode=SVARESA" 30 > "$TMP/e2e_t23_svaresa.txt"; cat "$TMP/e2e_t23_svaresa.txt"
eq svaramanas --ez on false; sleep 3
# Deliver real zero PCM on the SAME live, captured AudioTrack. Replacing it with
# a nocapture track now correctly removes the old muted UID on CLOSE, so that
# fixture no longer reaches the watchdog. Keep its audio and log assertions.
$A shell pm revoke $EQ android.permission.DUMP
tone $CAP --ef live_amp 0; sleep 14
tone $CAP --ef live_amp 0.25; sleep 2
measure "T20 captured stream after real silent PCM triggers fail-open (expect T1, not silence)"
eq stop_capture; sleep 3
$A shell pm grant $EQ android.permission.DUMP
tone $CAP --ez stop true; sleep 3
# Repeated recreation without session broadcasts: Bluetooth reconnects and
# player offload transitions often replace an AudioTrack this way. This tests
# session lifecycle recovery, not a physical Bluetooth transport.
eq preset; sleep 3
for cycle in 1 2 3; do
  $A logcat -c
  tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false
  wait_for "route: $CAP .*Engine A" 30; sleep 2
  measure "T24_$cycle recreated non-broadcasting session"
  if [ "$cycle" != 3 ]; then tone $CAP --ez stop true; sleep 2; fi
done
$A logcat -c
eq test_drop_system_effects
wait_for "route: $CAP .*Engine A" 30; sleep 2
measure "T25 lost effect recovers without player restart or manual refresh"
tone $CAP --ez stop true; sleep 3
# Detection must not hinge on ONE Android report: blind the player list, then the audio-server
# tables, and require a freshly started non-broadcasting player to be processed regardless.
for blind in "players" "server"; do
  $A logcat -c
  if [ "$blind" = players ]; then eq test_blind_reports --ez players true --ez server false; else eq test_blind_reports --ez players false --ez server true; fi
  sleep 2
  tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false
  wait_for "route: $CAP .*Engine A" 40; sleep 3
  measure "T26_$blind found with the $blind report unreadable"
  tone $CAP --ez stop true; sleep 3
done
eq test_blind_reports --ez players false --ez server false; sleep 2
# The real report must parse on this Android image, and the effect must be seen in the audio server.
$A logcat -c
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false
wait_for "detect: players=ok server=ok .*$CAP#[0-9]+:started:BOTH:mixer:PROCESSING" 40 > "$TMP/e2e_t27_detect.txt" || wait_for "detect: .*$CAP" 5 > "$TMP/e2e_t27_detect.txt"
tone $CAP --ez stop true; sleep 3
# Svaresa's quiet-listening adaptation must be audible on system effects (the default engine), not just planned.
# Tones at the test volume; headphone recognition and the clock are switched off to keep it deterministic.
# Auto headroom (default on) never lets a boost raise the absolute level (T9 needs it off), so the lift is a
# change of BALANCE: 63 Hz against 1 kHz, each measured with Svaresa resting and adapting.
SVARESA_QUIET="--ez on true --es mode SVARESA --es night OFF --ez auto_headphone false --ez volume_aware true --ez route_aware false"
eq engine_mode --ez system_only true; sleep 2
eq reset_sound; sleep 3
tone $CAP --ef freq 1000 --ef amp 0.25 --ez broadcast false
wait_for "route: $CAP .*Engine A" 30; sleep 3
eq svaramanas --ez on false; sleep 3
measure "T28_off_1k 1 kHz tone, Svaresa resting"
eq svaramanas $SVARESA_QUIET; sleep 6
measure "T28_on_1k 1 kHz tone, Svaresa quiet-listening"
eq svaramanas --ez on false; sleep 2
tone $CAP --ez stop true; sleep 3
tone $CAP --ef freq 63 --ef amp 0.25 --ez broadcast false
wait_for "route: $CAP .*Engine A" 30; sleep 3
$A logcat -c
eq svaramanas --ez on false
wait_for "svaramanas: resting" 15 > "$TMP/e2e_t28_rest.txt"; cat "$TMP/e2e_t28_rest.txt"; sleep 3
measure "T28_off 63 Hz tone, Svaresa resting"
$A logcat -c
eq svaramanas $SVARESA_QUIET
wait_for "svaramanas plan: mode=SVARESA" 30 > "$TMP/e2e_t28_plan.txt"; cat "$TMP/e2e_t28_plan.txt"
sleep 4
measure "T28_on 63 Hz tone, Svaresa quiet-listening lift"
$A logcat -c
eq svaramanas --ez on true --es mode SVARESA --es night ON --ez auto_headphone false
wait_for "svaramanas plan: mode=SVARESA" 30 > "$TMP/e2e_t29_plan.txt"; cat "$TMP/e2e_t29_plan.txt"
sleep 8
measure "T29 night comfort on the same tone (bounded, audible)"
eq svaramanas --ez on false; sleep 3
tone $CAP --ez stop true; sleep 3
# Restart discovery so screenshots include real detected app rows.
eq stop_system; sleep 3; eq start_system; sleep 3
tone $CAP --ef freq 1000 --ef amp 0.05 --ez broadcast true; sleep 4
tone $CAP --ez stop true; sleep 3

# ---- verdicts -------------------------------------------------------------
FULL="$TMP/e2e_eqspike_full.log"
lvl() { grep "^$1 " "$TMP/e2e_results.txt" | grep -oE 'median=-?[0-9.]+' | cut -d= -f2; }
RESP=$(grep -oE 'preset bands=.*response@1kHz=-?[0-9.]+' "$FULL" | tail -1 | grep -oE '[-0-9.]+$')
T0=$(lvl T0); T1=$(lvl T1); T2=$(lvl T2); T3=$(lvl T3); T4=$(lvl T4)
check() { # name ok? detail
  if [ "$2" = 1 ]; then echo "PASS $1 — $3"; else echo "FAIL $1 — $3"; fi >> "$TMP/e2e_results.txt"
}
near() { awk -v a="$1" -v b="$2" -v t="$3" 'BEGIN { d = a - b; if (d < 0) d = -d; print (a != "" && b != "" && d <= t) ? 1 : 0 }'; }
check "Missing detection permission and no source block silent capture startup" "$(grep -q 'capture: start blocked' "$TMP/e2e_t16_blocked.txt" && echo 1 || echo 0)" "$(cat "$TMP/e2e_t16_blocked.txt")"
EXP1=$(awk -v a="$T0" -v r="$RESP" 'BEGIN { print a + r }')
check "Engine A applies the curve" "$(near "$T1" "$EXP1" 1.0)" "T1=$T1, expected T0+($RESP)=$EXP1 ±1 dB"
B_ROUTE=$(grep -cE "route: $CAP .*Engine B" "$FULL")
check "Engine B takes the capturable app" "$([ "$B_ROUTE" -ge 1 ] && echo 1 || echo 0)" "Engine B routes for $CAP: $B_ROUTE"
check "Engine B: one processed copy, no double audio" "$(near "$T2" "$T1" 2.0)" "T2=$T2 vs T1=$T1 ±2 dB (double audio ≥ T0=$T0)"
BLK_B=$(grep -cE "route: $BLK .*Engine B" "$FULL"); BLK_A=$(grep -cE "route: $BLK .*Engine A" "$FULL")
check "Capture-blocked app stays on Engine A" "$([ "$BLK_B" -eq 0 ] && [ "$BLK_A" -ge 1 ] && echo 1 || echo 0)" "A=$BLK_A B=$BLK_B"
check "Blocked app still audible with EQ" "$(near "$T3" "$T1" 2.0)" "T3=$T3 vs T1=$T1 ±2 dB"
check "Non-broadcasting app found via DUMP" "$(near "$T4" "$T1" 2.0)" "T4=$T4 vs T1=$T1 ±2 dB"
T6=$(lvl T6); T7=$(lvl T7); T2B=$(lvl T2b); T8=$(lvl T8)
R8=$(grep -oE 'tuners vocal=VocalTuner\(intimacy=1.0.*response@1kHz=-?[0-9.]+' "$FULL" | tail -1 | grep -oE '[-0-9.]+$')
E8=$(awk -v a="$T0" -v r="$R8" 'BEGIN { print a + r }')
check "Orchestral amp leaves a centred source untouched" "$(near "$T2B" "$T2" 0.5)" "T2b=$T2B vs T2=$T2 ±0.5 dB"
check "Vocal tuner applies on system effects" "$(near "$T8" "$E8" 1.0)" "T8=$T8, expected $E8 ±1 dB"
R6=$(grep -oE 'bass preset=Punchy .*response@1kHz=-?[0-9.]+' "$FULL" | tail -1 | grep -oE '[-0-9.]+$')
R7=$(grep -oE 'tune: .*response@1kHz=-?[0-9.]+' "$FULL" | tail -1 | grep -oE '[-0-9.]+$')
E6=$(awk -v a="$T0" -v r="$R6" 'BEGIN { print a + r }'); E7=$(awk -v a="$T0" -v r="$R7" 'BEGIN { print a + r }')
check "Bass tuner applies on system effects" "$(near "$T6" "$E6" 1.0)" "T6=$T6, expected $E6 ±1 dB"
check "Bass dynamics didn't break system effects" "$(grep -q 'attach failed' "$FULL" && echo 0 || echo 1)" "no 'attach failed' in log"
TUNE=$(grep -oE 'tune: .*' "$FULL" | tail -1)
check "AutoEq tuning fetched and fitted" "$(echo "$TUNE" | grep -qE 'Sennheiser HD 650 \(oratory1990.*64 bands rms=0\.[0-4]' && echo 1 || echo 0)" "$TUNE"
check "Tuning applies on system effects" "$(near "$T7" "$E7" 1.0)" "T7=$T7, expected $E7 ±1 dB"
T9=$(lvl T9); T10=$(lvl T10); T11=$(lvl T11); T12=$(lvl T12); T13=$(lvl T13); T14=$(lvl T14); T15=$(lvl T15); T16=$(lvl T16)
E9=$(awk -v a="$T0" 'BEGIN { print a + 6 }')
check "System headroom switch permits measured +6 dB boost" "$(near "$T9" "$E9" 1.0)" "T9=$T9 expected $E9 ±1 dB"
check "EQ bypass restores level" "$(near "$T10" "$T0" 1.0)" "T10=$T10 expected $T0 ±1 dB"
check "Per-app system override has no duplicate capture" "$(near "$T11" "$T1" 2.0)" "T11=$T11 vs T1=$T1"
check "Changing app preference stops capture safely" "$(near "$T12" "$T1" 2.0)" "T12=$T12 vs T1=$T1"
check "Returning to Auto restores processed capture" "$(near "$T13" "$T1" 2.0)" "T13=$T13 vs T1=$T1"
check "Global system-only stops processed capture" "$(near "$T14" "$T1" 2.0)" "T14=$T14 vs T1=$T1"
check "System EQ remains effective behind the launcher" "$(near "$T15" "$T1" 2.0)" "T15=$T15 vs T1=$T1"
check "Undetected audio is excluded from capture" "$(near "$T16" "$T0" 1.0)" "T16=$T16 vs T0=$T0"
T17=$(lvl T17); T18=$(lvl T18); T19=$(lvl T19)
check "General player broadcasts connect without DUMP" "$(near "$T17" "$T1" 1.0)" "T17=$T17 vs T1=$T1"
check "Unknown player is not counted as a session route (pre-mix level unchanged)" "$(near "$T18" "$T0" 1.0)" "T18=$T18 vs T0=$T0"
MIXAF=$(python3 - "$TMP/e2e_mix_af.txt" <<'PY2'
import re,sys
t=open(sys.argv[1]).read()
blocks=re.split(r"\n\s*\d+ effects for session ", t)[1:]
print(1 if any(re.match(r"0\b", b) and "Dynamics Processing" in b and re.search(r"\n\s*00000\s+003\s+y\s+y", b) for b in blocks) else 0)
PY2
)
check "Hidden player gets whole-mix fallback EQ (active output-mix effect)" "$([ "$MIX_ON" = 1 ] && [ "$MIXAF" = 1 ] && echo 1 || echo 0)" "app state=$MIX_ON; session-0 DynamicsProcessing active=$MIXAF"
check "Whole-mix fallback switches off once the player is routed" "$MIX_OFF" "after the live grant routed the player"
check "Permission granted during playback takes effect without restarting" "$(near "$T19" "$T1" 1.0)" "T19=$T19 vs T1=$T1"
T20=$(lvl T20)
check "Silent capture fails open to Engine A (never leaves silence)" "$(near "$T20" "$T1" 3.0)" "T20=$T20 vs T1=$T1 ±3 dB"
check "Watchdog logged the fail-open" "$(grep -q 'failing open' "$FULL" && echo 1 || echo 0)" "log line present"
R21=$(grep -oE 'response@1kHz=-?[0-9.]+' "$TMP/e2e_t21_plan.txt" | tail -1 | grep -oE '[-0-9.]+$')
T21=$(lvl T21); E21=$(awk -v a="$T0" -v r="$R21" 'BEGIN { print a + r }')
check "Svaramanas plan reaches system effects" "$(near "$T21" "$E21" 1.0)" "T21=$T21, expected $E21 ±1 dB ($(sed 's/.*svaramanas plan: //' "$TMP/e2e_t21_plan.txt"))"
H22=$(grep -oE 'loudness=-?[0-9.]+' "$TMP/e2e_t22_heard.txt" | grep -oE '[-0-9.]+$')
check "Svaramanas hears the captured source" "$(awk -v l="$H22" 'BEGIN { print (l != "" && l > -60 && l < -3) ? 1 : 0 }')" "$(cat "$TMP/e2e_t22_heard.txt")"
check "Svaresa automatic mode reaches the native planner" "$(grep -q 'mode=SVARESA' "$TMP/e2e_t23_svaresa.txt" && echo 1 || echo 0)" "$(cat "$TMP/e2e_t23_svaresa.txt")"
for cycle in 1 2 3; do
  ACTUAL=$(lvl "T24_$cycle")
  check "Recreated non-broadcasting session cycle $cycle" "$(near "$ACTUAL" "$T1" 1.0)" "measured=$ACTUAL expected=$T1 ±1 dB"
done
T25=$(lvl T25)
check "Lost system effect recovers automatically on the existing session" "$(near "$T25" "$T1" 1.0)" "T25=$T25 expected=$T1 ±1 dB"
T26P=$(lvl T26_players); T26S=$(lvl T26_server)
check "Player found with the player list unreadable (audio-server tables only)" "$(near "$T26P" "$T1" 1.0)" "measured=$T26P expected=$T1 ±1 dB"
check "Player found with the audio-server report unreadable (player list only)" "$(near "$T26S" "$T1" 1.0)" "measured=$T26S expected=$T1 ±1 dB"
check "Audio-server report parses here and verifies the attached effect" "$(grep -q 'PROCESSING' "$TMP/e2e_t27_detect.txt" && echo 1 || echo 0)" "$(cat "$TMP/e2e_t27_detect.txt")"
T28OFF=$(lvl T28_off); T28ON=$(lvl T28_on); T29=$(lvl T29)
resp() { grep -oE "response@$1=-?[0-9.]+" "$2" | tail -1 | grep -oE '[-0-9.]+$'; }
R28=$(resp 63Hz "$TMP/e2e_t28_plan.txt"); R28K=$(resp 1kHz "$TMP/e2e_t28_plan.txt")
R0=$(resp 63Hz "$TMP/e2e_t28_rest.txt"); R0K=$(resp 1kHz "$TMP/e2e_t28_rest.txt")
E28=$(awk -v a="$T28OFF" -v r="$R28" -v r0="$R0" 'BEGIN { if (r0 == "") { print ""; exit } print a + r - r0 }')
check "Svaresa quiet-listening bass lift is audible on system effects" "$(near "$T28ON" "$E28" 1.0)" "T28_on=$T28ON, expected off($T28OFF)+response@63Hz(on $R28 − resting $R0)=$E28 ±1 dB ($(sed 's/.*svaramanas plan: //' "$TMP/e2e_t28_plan.txt"))"
T28OFFK=$(lvl T28_off_1k); T28ONK=$(lvl T28_on_1k)
REL=$(awk -v a="$T28ON" -v b="$T28OFF" -v c="$T28ONK" -v d="$T28OFFK" 'BEGIN { if (a == "" || b == "" || c == "" || d == "") { print ""; exit } print (a - b) - (c - d) }')
PREL=$(awk -v a="$R28" -v b="$R0" -v c="$R28K" -v d="$R0K" 'BEGIN { if (a == "" || b == "" || c == "" || d == "") { print ""; exit } print (a - b) - (c - d) }')
check "Svaresa lifted the bass relative to the mids (measured 63 Hz vs 1 kHz)" "$(awk -v r="$REL" -v p="$PREL" 'BEGIN { d = r - p; if (d < 0) d = -d; print (r != "" && p != "" && r > 2 && d <= 1) ? 1 : 0 }')" "measured balance change=$REL dB, predicted=$PREL dB (63 Hz: off=$T28OFF on=$T28ON; 1 kHz: off=$T28OFFK on=$T28ONK)"
check "Night comfort keeps the music audible and bounded" "$(awk -v n="$T29" -v off="$T28OFF" 'BEGIN { d = n - off; print (n != "" && off != "" && d > -9 && d < 4) ? 1 : 0 }')" "night=$T29 vs resting=$T28OFF ($(sed 's/.*svaramanas plan: //' "$TMP/e2e_t29_plan.txt"))"
log "results:"; cat "$TMP/e2e_results.txt"
$A logcat -d -s EqSpike:I > "$TMP/e2e_eqspike.log"
kill $FULLLOG 2>/dev/null
log "full app log: $TMP/e2e_eqspike.log"
if [ -n "${APK_DIR:-}" ]; then
  cp "$TMP/e2e_results.txt" "$TMP/e2e_eqspike.log" "$APK_DIR/" && log "copied results to $APK_DIR"
fi

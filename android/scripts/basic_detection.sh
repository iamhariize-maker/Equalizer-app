#!/usr/bin/env bash
# DUMP-free integration and downstream measurements, separate from all existing PASS sets.
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:?output directory required}; A=(adb -s "$S")
CAP=app.svan.testsource.capturable
mkdir -p "$OUT"
trap '"${A[@]}" logcat -d > "$OUT/logcat.txt" 2>&1 || true; "${A[@]}" shell pm grant app.svan android.permission.DUMP >/dev/null 2>&1 || true' EXIT
eq() { "${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
tone() { "${A[@]}" shell am start -W -n "$CAP"/app.svan.testsource.ToneActivity "$@" >/dev/null; }
status() {
  eq basic_status
  "${A[@]}" shell run-as app.svan cat files/basic-status.json > "$OUT/$1.json"
}
measure() {
  "${A[@]}" logcat -c; eq measure_mix --ef seconds 3
  for ((i=0;i<30;i++)); do
    line=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/.*MIX median=\([-0-9.]*\) .*/\1/p' | tail -1)
    if [[ -n "$line" ]]; then printf '%s\n' "$line" > "$OUT/$1.db"; return; fi
    sleep 1
  done
  return 1
}
eq stop_capture; sleep 3; eq shared_output --ez on false
eq reset_sound; eq svaramanas --ez on false; eq mix_fallback --ez on false; eq engine_mode --ez system_only true; eq start_system
"${A[@]}" shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
"${A[@]}" shell pm revoke app.svan android.permission.DUMP
"${A[@]}" shell pm grant app.svan android.permission.RECORD_AUDIO
# Manifest-targeted OPEN/CLOSE, duplicate package broadcasts and generic broadcasts are all exercised.
"${A[@]}" logcat -c
tone --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez component true
sleep 4
status component
measure per-player-flat
eq eq_band --ef frequency 1000 --ef gain -6
sleep 2
measure per-player-cut
tone --ez pause true; sleep 2; status paused
tone --ez pause false; sleep 3; status resumed
measure resumed-cut
eq test_drop_system_effects
sleep 8
status recovered
measure recovered-cut
for ((cycle=0;cycle<4;cycle++)); do
  tone --ez stop true; sleep 1
  tone --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez explicit false
  sleep 3
  status "cycle-$cycle"
done
# A source that never broadcasts must stay unidentified: no cached ID is an attachment authority.
tone --ez stop true; sleep 2
tone --ef freq 1000 --ef amp 0.25 --ez broadcast false
sleep 4
status hidden
"${A[@]}" logcat -d -s EqTestSource:I > "$OUT/source.txt"
SID=$(python3 - "$OUT/source.txt" <<'PY'
import re,sys
print(re.findall(r'playing .*session=(\d+).*broadcast=false',open(sys.argv[1]).read())[-1])
PY
)
measure hidden-flat
# Standard public control-panel contract supplies an actual ID, without enabling discovery permission.
"${A[@]}" shell am start -W -n app.svan/.EffectControlActivity \
  -a android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL \
  --ei android.media.extra.AUDIO_SESSION "$SID" --es android.media.extra.PACKAGE_NAME "$CAP" >/dev/null
sleep 4
"${A[@]}" exec-out screencap -p > "$OUT/effect-panel.png"
status panel
measure panel-cut
# An unrelated CLOSE cannot retire the panel connection.
"${A[@]}" shell am broadcast -n app.svan/.SessionReceiver \
  -a android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION \
  --ei android.media.extra.AUDIO_SESSION "$SID" --es android.media.extra.PACKAGE_NAME app.svan.testsource.blocked >/dev/null
sleep 1; status unrelated-close
# Release the real known connection, then replace it with another silent-announcement source.
"${A[@]}" shell am broadcast -n app.svan/.SessionReceiver \
  -a android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION \
  --ei android.media.extra.AUDIO_SESSION "$SID" --es android.media.extra.PACKAGE_NAME "$CAP" >/dev/null
tone --ez stop true; sleep 2
tone --ef freq 1000 --ef amp 0.25 --ez broadcast false
sleep 3
eq reset_sound; eq svaramanas --ez on false; eq mix_fallback --ez on false
measure shared-flat
eq shared_output --ez on true; sleep 3
status shared
eq eq_band --ef frequency 1000 --ef gain -6
sleep 2
measure shared-cut
eq start_capture --es quality EFFICIENT
sleep 2
status capture-blocked
"${A[@]}" shell input tap 224 600 # Hi-Fi tab on the 320x640 test emulator
sleep 2
for ((i=0;i<6;i++)); do
  "${A[@]}" shell uiautomator dump /sdcard/basic-ui.xml >/dev/null 2>&1
  if "${A[@]}" shell cat /sdcard/basic-ui.xml | grep -q 'Stop shared-output EQ'; then break; fi
  "${A[@]}" shell input swipe 160 470 160 200 350; sleep 1
done
"${A[@]}" exec-out screencap -p > "$OUT/shared-output.png"
tone --ef freq 1000 --ef amp 0.25 --ez broadcast true --ez component true
sleep 3; status shared-known; measure shared-known-cut
tone --ez stop true; sleep 2
tone --ef freq 1000 --ef amp 0.25 --ez broadcast false
sleep 3
eq shared_output --ez on false; sleep 2
status shared-stopped
measure shared-restored
eq shared_output --ez on true; sleep 2
eq test_output_change; sleep 2
status output-changed
python3 - "$OUT" <<'PY' | tee "$OUT/results.txt"
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);d=lambda n:json.loads((p/(n+'.json')).read_text());v=lambda n:float((p/(n+'.db')).read_text())
def a(n):return any(r['owner']=='ENGINE_A' and r['sid'] in d(n)['attached'] for r in d(n)['routes'])
def near(x,y):return abs(x-y)<=.75
names=['component','paused','resumed','recovered','hidden','panel','unrelated-close','shared','shared-known','capture-blocked','shared-stopped','output-changed']+[f'cycle-{i}' for i in range(4)]
checks=[('all basic-detection checks run with DUMP revoked',all(not d(n)['dump'] and not d(n)['reportAccess'] for n in names)),
 ('manifest session announcements apply measured per-player EQ',a('component') and near(v('per-player-cut')-v('per-player-flat'),-6)),
 ('pause/resume retains the same connection and measured response',a('paused') and d('paused')['routes']==d('resumed')['routes'] and near(v('resumed-cut'),v('per-player-cut'))),
 ('lost effects recover without discovery permission',a('recovered') and near(v('recovered-cut'),v('per-player-cut'))),
 ('session replacement reconnects repeatedly without stale active sessions',all(a(f'cycle-{i}') and len(d(f'cycle-{i}')['routes'])==1 for i in range(4))),
 ('unannounced replacement never inherits a cached session',not d('hidden')['routes'] and not d('hidden')['attached']),
 ('standard EQ panel connects a hidden player with measured EQ',a('panel') and near(v('panel-cut')-v('hidden-flat'),-6)),
 ('unrelated CLOSE preserves the actual connection',d('panel')['routes']==d('unrelated-close')['routes']),
 ('shared-output EQ reaches an unannounced source without per-player effects',d('shared')['sharedAttached'] and d('shared')['attached']==[0] and not d('shared')['routes'] and near(v('shared-cut')-v('shared-flat'),-6)),
 ('new announced sessions never stack EQ on the shared output',d('shared-known')['attached']==[0] and len(d('shared-known')['routes'])==1 and d('shared-known')['routes'][0]['owner']=='SHARED_OUTPUT' and near(v('shared-known-cut'),v('shared-cut'))),
 ('output-change handler retires the shared effect',not d('output-changed')['sharedRequested'] and 0 not in d('output-changed')['attached']),
 ('shared-output processing blocks Engine B and restores the original level on stop',not d('capture-blocked')['capture'] and d('capture-blocked')['sharedRequested'] and not d('shared-stopped')['attached'] and near(v('shared-restored'),v('shared-flat')))]
for n in ['per-player-flat','per-player-cut','resumed-cut','recovered-cut','hidden-flat','panel-cut','shared-flat','shared-cut','shared-known-cut','shared-restored']: print(f'INFO {n}: {v(n):.1f} dBFS (output-mix meter)')
for name,ok in checks:print(('PASS ' if ok else 'FAIL ')+name)
assert all(ok for _,ok in checks)
PY
tone --ez stop true; eq reset_sound

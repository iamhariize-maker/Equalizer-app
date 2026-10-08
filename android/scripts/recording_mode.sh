#!/usr/bin/env bash
# Real Engine B / MediaStore / AAC and recording UI evidence, separate from the existing PASS sets.
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-recording}; A=(adb -s "$S")
CAP=app.svan.testsource.capturable
mkdir -p "$OUT"
trap '"${A[@]}" logcat -d > "$OUT/logcat.txt" 2>&1 || true' EXIT
eq() { "${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
shot() { "${A[@]}" exec-out screencap -p > "$OUT/$1.png"; }
tap() {
  "${A[@]}" shell uiautomator dump /sdcard/recording-ui.xml >/dev/null 2>&1
  "${A[@]}" shell cat /sdcard/recording-ui.xml > "$OUT/ui.xml"
  local point
  point=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re,sys,xml.etree.ElementTree as E
node=next(n for n in E.parse(sys.argv[1]).iter('node') if n.get('text')==sys.argv[2])
b=list(map(int,re.findall(r'\d+',node.get('bounds'))))
assert b[2]>b[0] and b[3]>b[1]
print((b[0]+b[2])//2,(b[1]+b[3])//2)
PY
)
  "${A[@]}" shell input tap $point
}
evidence() {
  "${A[@]}" logcat -c; eq proof_status
  for ((i=0;i<20;i++)); do
    if "${A[@]}" logcat -d -s EqSpike:I | grep -q PROOF_EVIDENCE_READY; then
      "${A[@]}" shell run-as app.svan cat files/recording-evidence.json > "$OUT/evidence.json"
      return
    fi
    if "${A[@]}" logcat -d -s EqSpike:I | grep -q PROOF_FAILED; then exit 1; fi
    sleep 1
  done
  return 1
}
eq stop_capture; sleep 3
eq reset_sound; eq svaramanas --ez on false; eq engine_mode --ez system_only false
"${A[@]}" shell pm grant app.svan android.permission.RECORD_AUDIO
"${A[@]}" shell pm grant app.svan android.permission.DUMP
"${A[@]}" shell appops set app.svan PROJECT_MEDIA allow
"${A[@]}" shell am start -n "$CAP"/app.svan.testsource.ToneActivity --ef freq 1000 --ef amp 0.25 --ez broadcast true >/dev/null
sleep 6
"${A[@]}" logcat -c; eq start_capture --es quality EFFICIENT
ready=false
for ((i=0;i<60;i++)); do
  if "${A[@]}" logcat -d -s EqSpike:I | grep -q 'capture: started'; then ready=true; break; fi
  sleep 1
done
$ready
sleep 4
eq proof_start
sleep 2
shot recording
tap Sync
shot sync-flash-attempt
sleep 1
shot synced
tap 'Mark now'
shot mark-dialog
tap 'Segment label'
"${A[@]}" shell input text 'Manual_demo'
tap 'Mark now'
sleep 1
shot marked
# Clock and manual label must remain visible while EQ settings are edited.
tap EQ
sleep 1
shot recording-eq
cp "$OUT/ui.xml" "$OUT/marked-ui.xml"
tap Before
sleep 1
shot before
tap After
sleep 1
shot after
eq eq_band --ef frequency 1000 --ef gain -3
sleep 2
shot setting-change
tap Stop
for ((i=0;i<60;i++)); do
  evidence
  if python3 -c 'import json,sys;sys.exit(json.load(open(sys.argv[1]))["state"]!="Done")' "$OUT/evidence.json"; then break; fi
  sleep 1
done
STAMP=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["directory"])' "$OUT/evidence.json")
for file in svan-dry-input.wav svan-processed-output.wav svan-dry-from-sync.wav svan-processed-from-sync.wav svan-processed-sync-cue.wav svan-ab-timeline.wav svan-ab-timeline-from-sync.wav svan-proof-report.json svan-settings-effects.png; do
  "${A[@]}" exec-out run-as app.svan cat "files/proof/$STAMP/$file" > "$OUT/$file"
done
python3 - "$OUT" <<'PY' | tee "$OUT/results.txt"
import json,pathlib,sys,wave,struct,math
p=pathlib.Path(sys.argv[1]);d=json.loads((p/'evidence.json').read_text());r=d['report']
required={'svan-dry-input.wav','svan-processed-output.wav','svan-dry-from-sync.wav','svan-processed-from-sync.wav','svan-processed-sync-cue.wav','svan-processed-matched.wav','svan-processed-output.m4a','svan-proof-chart.png','svan-settings-effects.png','svan-proof-report.json'}
required.update({'svan-ab-timeline.wav','svan-ab-timeline-from-sync.wav'})
checks=[]
checks.append(('recording exports exist in MediaStore with identical nonempty payloads',required=={f['name'] for f in d['files']} and all(f.get('mediaStoreBytes',0)==f['privateBytes']>44 and f.get('bytesEqual') for f in d['files'])))
a=d['aac']; checks.append(('AAC-LC export has readable 48 kHz stereo packets',a['aacObjectType']==2 and a['mime']=='audio/mp4a-latm' and a['rate']==48000 and a['channels']==2 and a['firstPacketBytes']>0 and a['durationUs']>0))
checks.append(('sync trim uses the recorded frame without resampling',len(r['syncFrames'])==1 and all((p/full).read_bytes()[44+r['syncFrames'][0]*4:]==(p/tail).read_bytes()[44:] for full,tail in [('svan-dry-input.wav','svan-dry-from-sync.wav'),('svan-processed-output.wav','svan-processed-from-sync.wav')])))
checks.append(('manual and settled EQ edits get separate segments',any(s['change']=='Manual_demo' for s in r['segments']) and any('EQ curve' in s['change'] for s in r['segments'])))
with wave.open(str(p/'svan-ab-timeline.wav')) as w: ab_frames=w.getnframes()
with wave.open(str(p/'svan-ab-timeline-from-sync.wav')) as w: sync_ab_frames=w.getnframes()
with wave.open(str(p/'svan-dry-input.wav')) as w: full_frames=w.getnframes()
switches=r['abSwitches']; ab=r['abTimeline']
checks.append(('Before/After buttons export a sync-aligned RMS-matched timeline',
 [s['choice'] for s in switches]==['Before','After'] and switches[0]['frame']<switches[1]['frame']
 and all(abs(s['seconds']-s['frame']/r['sampleRateHz'])<1e-9 for s in switches)
 and ab['matchLevel'] and ab['crossfadeMs']==5 and ab['rangeGains']
 and ab_frames==full_frames-switches[0]['frame'] and sync_ab_frames==full_frames-r['syncFrames'][0]
 and any(s['change']=='Before' for s in r['segments']) and any(s['change']=='After' for s in r['segments'])))
def level(file,hz):
 with wave.open(str(p/file)) as w:
  values=struct.unpack('<'+'h'*(w.getnframes()*2),w.readframes(w.getnframes()))[::2]
 # Integer cycles from the start of the trimmed WAV; normalise to relative harmonic energy.
 n=min(len(values)//480*480,48000)
 re=sum(values[i]*math.cos(2*math.pi*hz*i/48000) for i in range(n));im=sum(values[i]*math.sin(2*math.pi*hz*i/48000) for i in range(n))
 return math.hypot(re,im)
ratio=lambda f:20*math.log10(max(1e-12,level(f,2000)/max(1e-12,level(f,1000))))
plain=ratio('svan-processed-from-sync.wav');cue=ratio('svan-processed-sync-cue.wav')
checks.append(('generated cue stays out of plain Engine B WAV',plain < -45 and cue > -35))
print(f'INFO cue 2 kHz / source 1 kHz: plain={plain:.2f} dB, alignment copy={cue:.2f} dB')
for name,ok in checks: print(('PASS ' if ok else 'FAIL ')+name)
assert all(ok for _,ok in checks)
PY
eq proof_dismiss
eq proof_countdown
sleep 1
shot countdown
sleep 4
shot countdown-started
tap Stop
for ((i=0;i<60;i++)); do
  evidence
  if python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));sys.exit(d["state"]!="Done")' "$OUT/evidence.json"; then break; fi
  sleep 1
done
cp "$OUT/evidence.json" "$OUT/countdown-evidence.json"
python3 - "$OUT/countdown-evidence.json" <<'PY' | tee -a "$OUT/results.txt"
import json,sys
r=json.load(open(sys.argv[1]))['report'];assert r['syncFrames']==[0] and r['syncSeconds']==[0]
print('PASS countdown starts with an exact frame-zero sync')
PY
eq stop_capture; eq proof_dismiss; eq reset_sound
"${A[@]}" shell am start -n "$CAP"/app.svan.testsource.ToneActivity --ez stop true >/dev/null

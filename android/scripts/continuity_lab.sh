#!/usr/bin/env bash
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-continuity}; A=(adb -s "$S")
mkdir -p "$OUT"
"${A[@]}" logcat -c
"${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd continuity_lab > "$OUT/start.txt"
ready=""
for ((attempt=0;attempt<90;attempt++)); do
  ready=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/^.*CONTINUITY_LAB_READY //p' | tail -1)
  [[ -n "$ready" ]] && break
  if "${A[@]}" logcat -d -s EqSpike:I | grep -q CONTINUITY_LAB_FAILED; then "${A[@]}" logcat -d -s EqSpike:I; exit 1; fi
  sleep 1
done
[[ -n "$ready" ]] || { echo 'FAIL continuity lab did not finish'; exit 1; }
printf '%s\n' "$ready" > "$OUT/lab.json"
python3 - "$OUT" <<'PY'
import json,pathlib,sys
out=pathlib.Path(sys.argv[1]);d=json.loads((out/'lab.json').read_text())
checks=[('backing vocal-region response through release JNI',2.3<d['backingDb']<2.6),
        ('spatial detail response through release JNI',1.7<d['spatialDb']<2.1),
        ('side bass level retained',abs(d['bassDb'])<.1),
        ('combined maximum tuners remain peak-protected',d['peak']<.93)]
text='\n'.join(('PASS' if valid else 'FAIL')+' '+label for label,valid in checks)+'\n'
(out/'results.txt').write_text(text);print(text,end='');print(json.dumps(d,indent=2))
assert all(valid for _,valid in checks)
PY

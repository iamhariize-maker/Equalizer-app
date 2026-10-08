#!/usr/bin/env bash
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-quality}; A=(adb -s "$S")
mkdir -p "$OUT"
"${A[@]}" install -r app/build/outputs/apk/release/app-release.apk >/dev/null
# Reinstalling a running foreground-service app can race Activity launch on a busy emulator.
# Start a fresh process and wait for Activity delivery; retain diagnostics on every failure.
trap '"${A[@]}" logcat -d > "$OUT/logcat-full.txt" 2>&1 || true' EXIT
"${A[@]}" shell am force-stop app.svan
"${A[@]}" logcat -c
"${A[@]}" shell am start -W -n app.svan/.Command --es cmd quality_lab > "$OUT/start.txt"
ready=""
for ((attempt=0;attempt<120;attempt++)); do
    ready=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/^.*QUALITY_LAB_READY //p' | tail -1)
    [[ -n "$ready" ]] && break
    if "${A[@]}" logcat -d -s EqSpike:I | grep -q QUALITY_LAB_FAILED; then "${A[@]}" logcat -d -s EqSpike:I; exit 1; fi
    sleep 1
done
[[ -n "$ready" ]] || { echo 'FAIL quality lab did not finish'; exit 1; }
printf '%s\n' "$ready" > "$OUT/lab.json"
python3 - "$OUT" <<'PY'
import json,pathlib,sys
out=pathlib.Path(sys.argv[1]);d=json.loads((out/'lab.json').read_text())
checks=[('release reconstructed peak protection',d['truePeakBefore']>1.3 and d['truePeakAfter']<.93),
        ('release selective resonance reduction',-1.7<d['dynamicDb']<-1.2),
        ('release measurement-derived correction fit',d['calibrationRmsDb']<.4),
        ('release blind comparison matches actual levels without boost',d['matchDb']<=.1 and d['trimOriginalDb']<=0 and d['trimProcessedDb']<=0)]
text='\n'.join(('PASS' if valid else 'FAIL')+' '+label for label,valid in checks)+'\n'
(out/'results.txt').write_text(text);print(text,end='');print(json.dumps(d,indent=2))
assert all(valid for _,valid in checks)
PY
"${A[@]}" shell am start -n app.svan/.Command --es cmd blind_lab >/dev/null
sleep 3
"${A[@]}" exec-out screencap -p > "$OUT/blind-listening-release.png"
"${A[@]}" shell input keyevent KEYCODE_BACK

bash scripts/continuity_lab.sh "$S" "$OUT/continuity"

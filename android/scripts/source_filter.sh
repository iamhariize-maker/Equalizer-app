#!/usr/bin/env bash
# Runs after the original routing suite using its installed debug app/test player.
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-source-filter}; A=(adb -s "$S")
CAP=app.svan.testsource.capturable
mkdir -p "$OUT"
: > "$OUT/results.txt"
trap '"${A[@]}" shell am start -n "$CAP/app.svan.testsource.ToneActivity" --ez stop true >/dev/null; "${A[@]}" logcat -d > "$OUT/logcat.txt"' EXIT
"${A[@]}" shell am start -n app.svan/.MainActivity --es cmd stop_capture >/dev/null
for spec in '5 4 notification' '1 4 media-labelled-interface' '1 2 music'; do
  read -r usage content label <<< "$spec"
  "${A[@]}" shell am start -n "$CAP/app.svan.testsource.ToneActivity" --ef amp 0.03 --ez broadcast false --ei usage "$usage" --ei content "$content" >/dev/null
  success=0
  for ((attempt=0;attempt<20;attempt++)); do
    sleep 1
    "${A[@]}" logcat -c
    "${A[@]}" shell am start -W -n app.svan/.MainActivity --es cmd source_policy_state --es pkg "$CAP" >/dev/null
    state=$("${A[@]}" logcat -d -s EqSpike:I | sed -n 's/^.*SOURCE_POLICY_STATE //p' | tail -1)
    if [[ -n "$state" ]] && python3 - "$label" "$state" <<'PY'
import json,sys
label=sys.argv[1];d=json.loads(sys.argv[2])
valid=d['observed']>0 and (d['routes']>0 and d['excluded']==0 if label=='music' else d['routes']==0 and d['excluded']>0)
raise SystemExit(0 if valid else 1)
PY
    then success=1;printf '%s\n' "$state" > "$OUT/$label.json";break;fi
  done
  if [[ "$success" == 1 ]]; then echo "PASS source filter $label" | tee -a "$OUT/results.txt"
  else echo "FAIL source filter $label" | tee -a "$OUT/results.txt";exit 1;fi
  "${A[@]}" shell am start -n "$CAP/app.svan.testsource.ToneActivity" --ez stop true >/dev/null
  sleep 2
done

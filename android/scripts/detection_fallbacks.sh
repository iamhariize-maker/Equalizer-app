#!/usr/bin/env bash
# Called after onboarding_e2e with its installed debuggable APK and no DUMP grant.
# Uses real MediaSessionManager + Android notification-listener binding, never fixture discovery.
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-fallbacks}
mkdir -p "$OUT"
A=(adb -s "$S")
CAP=app.svan.testsource.capturable
LISTENER=app.svan/app.svan.PlayerRecognitionService
: > "$OUT/results.txt"
eq() { "${A[@]}" shell am start -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
tone() { "${A[@]}" shell am start -n "$CAP"/app.svan.testsource.ToneActivity "$@" >/dev/null; }
cleanup() {
    "${A[@]}" shell cmd notification disallow_listener "$LISTENER" >/dev/null 2>&1 || true
    tone --ez stop true >/dev/null 2>&1 || true
}
trap cleanup EXIT
check() {
    local name=$1 expression=$2
    for ((attempt=0;attempt<20;attempt++)); do
        eq onboarding_state
        sleep 1
        "${A[@]}" shell run-as app.svan cat files/onboarding-state.json > "$OUT/state.json"
        if python3 - "$OUT/state.json" "$expression" <<'PY'
import json,sys
s=json.load(open(sys.argv[1]))
sys.exit(0 if eval(sys.argv[2],{'__builtins__':{}},{'s':s}) else 1)
PY
        then printf 'PASS %s\n' "$name" >> "$OUT/results.txt"; return; fi
    done
    printf 'FAIL %s\n' "$name" >> "$OUT/results.txt"
    cat "$OUT/state.json"
    exit 1
}
# Guarantee this is the independent no-Shizuku path, including persisted manager authorization.
"${A[@]}" shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
"${A[@]}" shell pm revoke app.svan android.permission.DUMP
"${A[@]}" shell cmd notification allow_listener "$LISTENER"
tone --ef freq 1000 --ef amp 0.1 --ez broadcast false
check 'media recognition names a real playing app without inventing an EQ route' \
    "s['recognition'] and s['recognizedTestPlayer'] and s['namedTestPlayer'] and s['connectedPlayers']==0 and s['kind']=='UNREACHABLE' and not s['dump'] and not s['capture'] and s['engineMode']=='SYSTEM_ONLY'"
"${A[@]}" exec-out screencap -p > "$OUT/recognized-without-audio.png"
tone --ez stop true
check 'stopping media clears recognition without leaving stale playing identity' "not s['recognized'] and s['kind']=='IDLE'"
tone --ef freq 1000 --ef amp 0.1 --ez broadcast true
check 'real session broadcasts pair with recognition and retain System effects' \
    "s['recognition'] and s['recognizedTestPlayer'] and s['connectedPlayers']==1 and s['kind']=='ROUTED' and not s['dump'] and not s['capture']"
"${A[@]}" shell cmd notification disallow_listener "$LISTENER"
check 'revoking optional access clears recognition while broadcast EQ remains connected' \
    "not s['recognition'] and not s['recognized'] and s['kind']=='ROUTED' and s['connectedPlayers']==1 and not s['dump']"
cat "$OUT/results.txt"

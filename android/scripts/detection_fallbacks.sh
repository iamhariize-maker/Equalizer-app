#!/usr/bin/env bash
# Called after onboarding_e2e with its installed debuggable APK and no DUMP grant.
# Proves the no-Shizuku basic path works with real session broadcasts, and that the
# installed app exposes no notification listener (Play Protect fraud-risk trigger).
set -euo pipefail
S=${1:-emulator-5554}; OUT=${2:-/tmp/svan-fallbacks}
mkdir -p "$OUT"
A=(adb -s "$S")
CAP=app.svan.testsource.capturable
: > "$OUT/results.txt"
eq() { "${A[@]}" shell am start -n app.svan/.MainActivity --es cmd "$@" >/dev/null; }
tone() { "${A[@]}" shell am start -n "$CAP"/app.svan.testsource.ToneActivity "$@" >/dev/null; }
cleanup() {
    "${A[@]}" logcat -d > "$OUT/logcat.txt" 2>&1 || true
    "${A[@]}" shell dumpsys audio > "$OUT/audio.txt" 2>&1 || true
    tone --ez stop true >/dev/null 2>&1 || true
}
trap cleanup EXIT
check() {
    local name=$1 expression=$2
    for ((attempt=0;attempt<20;attempt++)); do
        eq onboarding_state
        sleep 1
        "${A[@]}" shell run-as app.svan cat files/onboarding-state.json > "$OUT/state.json"
        if python3 - "$OUT/state.json" "$expression" <<'PY2'
import json,sys
s=json.load(open(sys.argv[1]))
sys.exit(0 if eval(sys.argv[2],{'__builtins__':{}},{'s':s}) else 1)
PY2
        then printf 'PASS %s\n' "$name" >> "$OUT/results.txt"; return; fi
    done
    printf 'FAIL %s\n' "$name" | tee -a "$OUT/results.txt"
    cat "$OUT/state.json"
    exit 1
}
# Guarantee this is the independent no-Shizuku path, including persisted manager authorization.
"${A[@]}" shell 'for p in $(pidof shizuku_server); do kill "$p"; done'
"${A[@]}" shell pm revoke app.svan android.permission.DUMP
"${A[@]}" shell dumpsys package app.svan > "$OUT/package.txt"
if grep -q "NotificationListenerService\|BIND_NOTIFICATION_LISTENER_SERVICE" "$OUT/package.txt"; then
    echo "FAIL installed app declares no notification listener" | tee -a "$OUT/results.txt"; exit 1
fi
echo "PASS installed app declares no notification listener" >> "$OUT/results.txt"
# Give the player's activity time to start its foreground service before Svan's activity is launched
# on top of it (an immediate launch can win the race on API 34 and the tone never plays).
tone --ef freq 1000 --ef amp 0.1 --ez broadcast true
sleep 4
check 'basic session broadcasts reach System effects without Shizuku' \
    "s['connectedPlayers']==1 and s['kind']=='ROUTED' and not s['dump'] and not s['capture']"
tone --ez stop true
sleep 3
check 'stopping the player returns basic detection to idle' "s['kind']=='IDLE'"
cat "$OUT/results.txt"

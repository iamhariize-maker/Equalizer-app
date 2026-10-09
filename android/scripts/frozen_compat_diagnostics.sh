#!/usr/bin/env bash
# CI-only early logs and host diagnostics for the unchanged release smoke test.
set -uo pipefail
OUT=${1:?output directory}; mkdir -p "$OUT"
export PATH="$GITHUB_WORKSPACE/tools/ci:$PATH"
adb logcat -c
adb logcat -v threadtime > "$OUT/logcat-stream.txt" 2>&1 &
SVAN_COMPAT_LOG_PID=$!
(
  while true; do
    date -u '+%Y-%m-%d %H:%M:%S UTC'
    free -m
    ps -C qemu-system-x86_64 -o pid,rss,comm
    sleep 2
  done
) > "$OUT/host-memory.txt" 2>&1 &
SVAN_COMPAT_MEMORY_PID=$!
trap 'kill "$SVAN_COMPAT_LOG_PID" "$SVAN_COMPAT_MEMORY_PID" 2>/dev/null || true' EXIT
adb devices -l > "$OUT/devices-before.txt"
adb shell getprop > "$OUT/properties-before.txt"
timeout --kill-after=5s 240s bash scripts/smoke_release.sh emulator-5554 > "$OUT/smoke.txt" 2>&1
SVAN_COMPAT_RESULT=$?
sudo dmesg --ctime > "$OUT/host-kernel.txt" 2>&1 || true
adb devices -l > "$OUT/devices-after.txt"
exit "$SVAN_COMPAT_RESULT"

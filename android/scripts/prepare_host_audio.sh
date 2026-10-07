#!/usr/bin/env bash
# CI-only host loopback. No app permissions or Android capture policy is changed.
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y -qq pulseaudio pulseaudio-utils
pulseaudio --start --exit-idle-time=-1
pactl load-module module-null-sink sink_name=svan_e2e rate=48000 channels=2 format=float32le
pactl set-default-sink svan_e2e
pactl set-sink-volume svan_e2e 100%
pactl set-default-source svan_e2e.monitor
# The emulator does not consistently discover PulseAudio's per-user socket. Give
# both its legacy QEMU backend and libpulse an explicit runner-local endpoint.
pactl load-module module-native-protocol-unix socket=/tmp/svan-e2e-pulse.sock auth-anonymous=1
printf '%s\n' 'PULSE_SERVER=unix:/tmp/svan-e2e-pulse.sock' \
  'QEMU_PA_SERVER=unix:/tmp/svan-e2e-pulse.sock' 'QEMU_PA_SINK=svan_e2e' >> "$GITHUB_ENV"
pactl list short sinks
pactl list short sources

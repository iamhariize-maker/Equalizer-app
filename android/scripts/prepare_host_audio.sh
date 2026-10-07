#!/usr/bin/env bash
# CI-only host loopback. No app permissions or Android capture policy is changed.
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y -qq pulseaudio pulseaudio-utils
pulseaudio --start --exit-idle-time=-1
pactl load-module module-null-sink sink_name=svan_e2e rate=48000 channels=2
pactl set-default-sink svan_e2e
pactl set-sink-volume svan_e2e 100%
pactl set-default-source svan_e2e.monitor
pactl list short sinks
pactl list short sources

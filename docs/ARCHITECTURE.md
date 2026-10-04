# Architecture

## Two global engines, one preset

```
              ┌──────────── preset (parametric bands, AutoEq import) ────────────┐
              │                                                                  │
   Engine A: system effects                                  Engine B: capture + re-render
   (Wavelet / Poweramp EQ approach)                          (RootlessJamesDSP approach)
   DynamicsProcessing attached to                            AudioPlaybackCapture → C++ core → AudioTrack
   each app's audio session                                  full DSP: parametric, oversampling,
   - gain per band only (no Q / filter type)                   resampling, dither, AGP
   - works with Spotify & DRM apps                           - apps that opt out of capture are unseen
   - low latency, low battery                                - adds latency; needs MediaProjection
```

The **native core renders the parametric curve** for both engines. Engine B runs
the full chain. Engine A samples the same curve into N log-spaced
DynamicsProcessing bands (`GlobalEqEngine.applyCurveFrom`), so one preset
drives both, with Engine A approximating it.

Per-app engine choice is the long-term goal: Engine B where capture is allowed,
Engine A elsewhere.

## Core signal chain (Engine B, future player mode)

```
float in → 64-bit → preamp + auto-headroom → [oversample ↑ 2/4/8x]
        → parametric EQ (up to 128 biquads/ch, TDF-II, double)
        → [oversample ↓] → Automatic Gain Protection → dither → float out
                                      (+ Resampler where rates differ)
```

## Threading model

- UI thread: `Engine::setBands`, `setPreampDb`. These take a mutex and publish the change.
- Audio thread: `Engine::process`. It only `try_lock`s that mutex. If the lock is busy it keeps the old coefficients for one more block. It never allocates.
- TSan-verified (`eq_parameter_updates_from_another_thread_are_safe`).


## Capture admission and foreground lifetime (2026-10-04)

SystemEqService owns Engine A’s foreground lifetime and optional DUMP-based
session discovery. CaptureService owns Engine B’s projection grant and audio loop.
The shared repository applies headroom/protection choices to both paths.

Engine B no longer records an unrestricted mix. SessionRouter publishes an
immutable set of UIDs only after their known sessions have been muted. A UID with
an unmuted/probing/unknown routed session is not admitted; a conflicting active
route stops capture to avoid silencing a muted source. The audio loop rebuilds
its AudioRecord only when admission changes. An empty set uses Svan’s own
capture-blocked UID as a silent sentinel, never a wildcard. Actual source playback
stops before normal source mutes are released on capture shutdown.

Per-app preferences are frozen for a projection session. Changing one stops that
session; the user restarts capture with a fresh permission grant. Capture-blocked
apps remain on Engine A regardless of Auto selection. No discovery means no
processing claim: without broadcasts or the optional DUMP grant, an app can stay
undetected and unprocessed, but its audio will not be duplicated by Engine B.

The Android output remains 48 kHz float. Core resampling is not wired into this
path. Small DSP filter delay is distinct from capture buffers, Android mixing,
Bluetooth encoding/transport and source-player buffers. Hi-Fi’s queue and CPU
readings are diagnostics rather than end-to-end latency or listening-quality scores.

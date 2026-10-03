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

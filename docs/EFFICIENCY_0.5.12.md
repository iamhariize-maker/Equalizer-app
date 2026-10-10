# Svan 0.5.12 — background efficiency and interaction work

This update targets redundant scheduling, file writes and UI work. It does not lower the selected
audio quality to save power. No battery-life percentage or listener preference is established by
these changes; the TECNO/HiOS and LG V60 runs below remain necessary.

## Audio invariants

- `core/`, the JNI bridge, all six Lab response models and the Equalizer coefficient table are
  unchanged from the delivered 0.5.11 source (`e5b4241`). A SHA-256 manifest checks every tracked
  file in those directories. No new audio-thread work is introduced.
- Oversampling, sample-rate negotiation, block/buffer sizes, native analysis, limiter, reconstructed
  peak protection, smoothing/slew rates, muting, capture admission and fail-open rules are preserved.
- System-effect updates keep the same float conversion, 0.01 dB update threshold and stable
  cuts-before-boosts ordering. The ordering implementation is checked against the old sorted
  implementation over 500 randomized 128-band transitions, including NaNs and threshold boundaries.
- Basic active playback/known connections still receive a one-second repair watchdog. Playback,
  OPEN/CLOSE, device, foreground and wake events still request an immediate discovery pass. The
  existing 1.5-second close grace and one-engine ownership are unchanged.

## Changes

1. One discovery timer serves both the periodic watchdog and settling checks. Coincident deadlines
   cause one scan. Repeated callbacks retain early settling checks and a bounded latest tail;
   expired deadlines after a delayed wake cause one scan. The existing in-flight scan gate remains.
2. An idle watchdog uses 30 seconds only when the public playback count is zero, there are no known
   sessions, capture is stopped and callback registration succeeded. Unknown public evidence or a
   failed callback registration uses five seconds. Enhanced active detection stays at five seconds.
   No wake lock or alarm was added. An OEM that suppresses callbacks and announcements after this
   positive idle snapshot can delay the fallback discovery by up to 30 seconds; verify that on the
   actual devices before treating idle scheduling as qualified for them.
3. UI state collectors follow the Activity lifecycle. Hi-Fi, EQ connection status and diagnostic
   polling stop below RESUMED, then read fresh state when resumed. Recording's visible clock pauses
   its drawing loop while hidden; the recording and its audio frame clock continue in the service.
   Recording countdown/cue events remain live to preserve completion and avoid replaying an old flash.
4. A resting or hidden Svaramanas/Svaresa mark creates no infinite pulse animation. Visible listening
   retains the existing pulse and timing. Response graphs reuse their paths and cache their exact
   sampled curve by sound inputs, including protection/headroom settings, rather than unrelated edits.
5. The smart planner coalesces pending requests, preserving an immediate edit over a periodic request.
   It reads the current request on its single worker and retains the three-second adaptive cadence.
   A disabled controller has no periodic delay timer.
6. Saved EQ serialization is separate from effect application. Live smart layers and compare, which
   were already absent from saved JSON, cannot cause identical settings rewrites. Genuine user edits
   still persist without a new debounce delay. Unchanged audio settings are a no-op.

## Evidence and its limits

Deterministic 30-minute scheduling simulation: idle watchdogs decrease from 360 at five seconds to
60 at thirty seconds (83.3% fewer scheduled watchdog scans). Active basic playback retains 1,800
watchdogs over the same interval. This measures deadlines, not CPU time, device wakeups in deep sleep
or battery energy; callback/recovery work is additional. The original Handler never woke a sleeping
phone on its own either.

`android/app/src/test/java/app/svan/EfficiencyTest.kt` covers scheduling, callback storms, delayed wake,
planner coalescing, band ordering, exact curve-cache inputs and persisted-state identity.
`android/scripts/efficiency.py` observes debug counters using read-only `run-as` receipts while the
Activity remains hidden. It verifies zero hidden UI polls, zero disabled planning, idle discovery,
foreground resume, live smart cadence and saved quality. Counters never run on the audio thread and
are disabled in the delivered non-debug APK. CI runs it on API 33 and 34 alongside existing routing,
basic detection, Lab, production and appearance checks. Passing synthetic fixtures is not Apple
Music, YouTube Music or OEM qualification.

Validation results, frozen APK identity and CI links are recorded in the delivered validation package.

## Phone battery and interaction protocol

Install over the previous preview; retain presets and the same quality/theme/detection settings.
Compare 0.5.11 and 0.5.12 on each phone with equal starting charge, brightness, route and volume.
Disable automatic app updates during the comparison. Repeat each condition at least three times,
alternating build order; use the same downloaded playlist to avoid network differences.

- Idle, screen off: 60 minutes with the system service enabled, smart control off, no player.
- Apple Music and YouTube Music: 60 minutes each on the usual USB/Bluetooth route, screen off,
  smart on and off. Skip tracks and let tracks end; check that EQ remains connected and settings
  are preserved. Do not change oversampling or quality between runs.
- Capture, where permitted: repeat at the same selected quality. Report any underrun/fail-open;
  savings here are limited by the unchanged audio processing workload.
- Feel: visit all tabs, drag controls quickly, switch themes, return after five minutes hidden,
  and try large text. Record visible stalls or stale connection status.

Record Android's app battery estimate and overall charge change with duration/temperature/route.
Those estimates are noisy; a power monitor or longer repeated controlled runs settle an energy
claim. To settle a smoothness claim, collect device frame timing before/after on the same gestures;
reduced background counters alone do not establish a frame-time improvement.

# Integrated Svan Lab and basic connection stability — 0.5.11 preview

The owner supplied `Svan-0.5.10-capture-status-c17357a-for-owner.apk` and reports
Apple Music/YouTube Music discovery working without enhanced detection, but Apple
connections dropping at track boundaries. This update retains that source plus
the three existing research commits on `ccr-48b03ead-4py4vd`.

## Connection changes

Engine A CLOSE announcements now start a bounded 1.5-second teardown grace.
A same-ID OPEN cancels teardown and keeps a healthy effect; an unrelated package
cannot close it. Duplicate CLOSE does not extend the deadline. New IDs still
need genuine announcements or privileged reports: no closed ID or anonymous
playback callback grants new attachment authority. The queue is bounded, clears
on shutdown, and repairs never resurrect pending-close sessions. A real CLOSE
expires and releases the effect. During the grace, the existing route still
blocks output-mix fallback, avoiding stacked equalization.

Capture and probing connections close immediately, preserving fail-open source
muting and one-engine ownership. Basic active-playback repairs run every second,
with five-second idle checks, in addition to the existing callback recovery ladder.
This addresses concrete lifecycle weaknesses; it does not establish the root cause
on Apple Music's physical-phone build without its diagnostic report.

## One app, one routing service

The Lab tab now has Shape, Engine and Measure pages. Device probes/logs remain
accessible from Measure. Shape uses the combined main Svan curve; its editor is
the normal EQ workspace. Engine offers 44.1/48k rate assumptions, 2048/4096/8192
FFT assumptions, a 3–18 dB operating margin, numerical response fitting and an
optional reference Equalizer blend. Controls use a reduced 64-band, unique-bin
layout and an AOSP-reference WOLA transfer/modulation model. The preferred frame
duration stays just below the power-of-two rounding boundary.
The frozen target uses a dense logarithmic grid, including DC separately, so a
narrow fractional bass peak is not lost between coarse uniform samples.

Fitted controls go through `GlobalEqEngine`, with the existing `SessionRouter`
and foreground service. There is no second service or competing per-session
DynamicsProcessing owner. Hybrid Equalizer instances are owned/released with
that chain, require the reference descriptor and five matching centers/range,
and never bypass Android control checks. A refusal on an existing route restores
normal Svan. Capture must be stopped before applying a Lab plan. Existing MBC,
limiter, spatial/capture processors and recording features remain available.

Fits are frozen experiments, not automatic defaults. Sound/settings changes,
stop or restart restore normal processing. A fresh fit first restores normal
Svan; a response outside the planner's −18/+12 dB range is refused. Extremely
deep cuts, high-pass/notch zeros or extreme preamp values therefore need normal
Svan rather than this bounded fit. An automatic controller that continues changing
the curve invalidates its fit; use the existing manual EQ option for a stable trial.

Measure exports a quiet 20-second PCM16 stereo WAV and a JSON control report.
The WAV is a stimulus, not a recorded output. The report records assumptions,
source settings, fingerprint, controls and predicted metrics. No additional
recorder, microphone consent, notification access, DUMP grant or Shizuku setup is
introduced. Imported/vendor-measured control profiles from the standalone trial
are not supported in this integrated version.

## Mint Circuit appearance

Appearance includes **Mint Circuit**, with the standalone Lab's charcoal/mint
palette, sans-serif headings, restrained grid artwork and flat accents across
all screens. Existing themes and saved sound state remain separate. The original
Svan branding, EQ title, dock and large-text navigation remain.

## Evidence and limits

See `validation-0.5.11.md` for this revision's checks. Planner metrics are
predictions of static EQ; MBC/limiter response, device FFT, actual sample rate,
audio latency, clipping, true peaks, commercial-player transitions, screen-off
survival and listening preference still require device measurements. Input gain
comes from the realized modeled maximum plus the chosen margin; this is not an
arbitrary-waveform sample-peak or BS.1770 true-peak guarantee. Existing native
reconstructed-peak protection belongs to Engine B, not Android DynamicsProcessing.

The uploaded APK's preview certificate is
`fd7955d137a0a85f218c144e26ea4c0c5bc69f0d7b2c82257016212fefa8c72a`.
Delivery must match it and increment versionCode 17 to 18. It is not the separate
owner production signer or a Play release.

Reference implementation sources (read, no GPL DSP copied):

- [AOSP DynamicsProcessing frequency core](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp)
- [AOSP DynamicsProcessing base](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/dsp/DPBase.cpp)
- [AOSP Equalizer biquads](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/lvm/lib/Eq/src/)
- [Android audio-effect session announcements](https://developer.android.com/reference/android/media/audiofx/AudioEffect#ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
- [Public playback callback](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback)
- [Existing source-research ledger and unverified items](SYSTEM_EFFECTS_FRAMEWORK.md)

Phone checks: install over the supplied preview without uninstalling; use basic
detection with Apple Music and YouTube Music, auto-advance, skip repeatedly, pause/
resume, screen off and switch USB/Bluetooth routes. Share a diagnostic while a
failure is happening. For Lab, start in manual mode at 48k/4096/6 dB, compare
normal/fitted at matched listening level and export a report plus external recording.

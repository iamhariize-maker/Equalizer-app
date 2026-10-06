# 0.5.6 — playback continuity and stereo detail

The owner reports frustrating crackling during playback and utility apps appearing as audio sources.
This revision addresses demonstrated discontinuity risks in code. The cause of the owner's specific
phone/output crackling is not established without its diagnostic report and a listening comparison.
It does not claim universal crackle-free playback or acoustic/hearing protection.

## Continuity changes

- Stereo parameters crossfade between fixed-size filter banks over 20 ms, including enable/bypass.
  Edits during a fade coalesce; identical publications preserve filter history. No audio-thread
  allocation or waiting is added to the native stereo processor.
- Bass-character bypass returns to unity over 10 ms rather than jumping from its current envelope.
- Unchanged EQ/tuner settings are not repeatedly rebuilt, and the in-memory diagnostic log is
  bounded so a long listening session does not grow its UI copy indefinitely.
- Android system effects retain a neutral four-band compressor instead of detaching/recreating the
  effect whenever dynamics turn on/off. Unchanged compressor and limiter settings are not resent.
  EQ attenuation is sent before increases to avoid temporarily stacking old and new boosts.
- System-effect attachment first uses a minimal muted bootstrap handle to disable an existing
  module before the full constructor's per-band configuration writes, then enables the final
  configured handle. Android's native DynamicsProcessing owns the stream-volume output gain, and
  its architecture configuration resets that gain. A fresh enabled transition makes Android
  resend the stream volume, including when reacquiring an already enabled effect after restart.
- Capture output is primed before play. Buffer capacity permits automatic growth on new underruns;
  it never shrinks mid-song. Three consecutive reporting windows with new underruns at capacity
  end capture, stop its output, then return source sessions to system effects. A visible message
  explains the fallback. This trades some latency for continuity; Bluetooth latency is not solved.
- Source-list changes fade the outgoing block and fade in the reopened capture. Recorder changes
  can still introduce a gap; the fade does not make Android recorder reconfiguration seamless.
- Quality/dither changes take effect on capture restart. Allocating a replacement DSP engine and
  changing filter latency no longer happen in the live capture loop.
- Audio-server diagnostic reads are spaced at least 1.5 seconds apart, reducing report-lock churn
  during playback callbacks. Failed/skipped reports remain unknown, not proof of absent sessions.

## Music-source policy

Both discovery and routing reject non-capturable usage (notifications, calls, alarms, UI sounds),
sonification content, SoundPool effect players, Android system UIDs across profiles, and known
utility packages including Rapido/System UI/face-unlock packages. These sounds stay on Android's
normal path: Svan does not silence notifications or disable face unlock.

Known music apps and explicitly music-labelled content are admitted immediately. Other newly
observed players require started observations spanning at least 1.5 seconds. Existing admitted
sessions survive pauses and unavailable reports. This is an attribute/evidence policy, not an AI
classifier: a persistent app that falsely labels sounds as music can still pass. OEM package and
attribute differences require real-device evidence. Explicit effect-open broadcasts remain a
permission-free discovery path, checked against available report evidence.

Capture now matches MEDIA usage only. Unknown/game playback can use system effects. Capturing a
UID whose other active media session is not muted is forbidden, including ignored utility-like
sessions sharing that UID. Routing batches publish their final allowlist together, avoiding an
intermediate mixed-ownership list during startup/reconciliation. Source audio blocked by capture
policy remains on system effects. This continuity change adds no notification listener or
accessibility permission.
The separate OEM detection follow-up now provides optional package/playback-state recognition;
it does not supply session IDs or capture authority. See DETECTION_FALLBACKS.md. These continuity
controls and music-source admission do not require notification access or accessibility access.
Definitive CLOSE broadcasts remove their old scan evidence before publishing the capture allowlist;
otherwise an old started record could look like an unmuted sibling after the player opens a new
session. Rejected active media retains its evidence and still excludes its UID. Shutdown clears
the old ledger. A reused session ID cannot borrow a different UID's mute.

## Two separate orchestral controls

- **Backing vocals:** a broad 1600 Hz, Q 0.65 side-channel bell, up to 2.5 dB.
- **Spatial detail:** a 4000 Hz side-channel high shelf, up to 2 dB.

Both are 0–100%, default off, persisted/exported/restored, retained alongside Svaresa suggestions,
and included in blind-comparison rendering. Existing settings migrate to zero for both. They use
the existing 180 Hz side crossover, leave mono/centre-only content unchanged, and add no reverb,
time delay, generated harmonics or fake binaural cues. The existing stereo-linked reconstructed-
peak limiter protects the combined chain when gain protection is enabled.

These are tonal M/S controls, not stem separation: stereo instruments sharing the bands change
with harmonies. Centre-panned backing vocals are not independently boosted. Spatial detail changes
the recorded interchannel balance and can change localization; it cannot promise more accurate
binaural reproduction or recover the artist's intent. Use modest settings and the level-matched
Lab comparison. The orchestral controls require the capture engine; system effects cannot do M/S.

## Research basis and design choice

- Android [playback capture rules](https://developer.android.com/media/platform/av-capture):
  capture eligibility, usage and UID matching, and capture opt-outs. This API does not classify
  music versus alerts that an app labels incorrectly.
- Android [AudioTrack underrun documentation](https://developer.android.com/reference/android/media/AudioTrack#getUnderrunCount()):
  underruns can create glitches/pops; larger effective buffers can reduce them.
- Android [audio timing guidance](https://developer.android.com/games/sdk/oboe/low-latency-audio):
  bounded work, no allocation/locks/heavy one-off calculation in callbacks, and buffer tuning.
  Svan still uses its existing AudioRecord/AudioTrack worker; this is not an Oboe migration.
- AOSP [DynamicsProcessing volume handling](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/EffectDynamicsProcessing.cpp):
  `EFFECT_CMD_SET_VOLUME` supplies the native channel output gain and returns unity to the mixer.
  Do not change the user's stream-volume index to compensate for an effect initialization problem.
- Rouard, Massa & Défossez, [Hybrid Transformers for Music Source Separation](https://arxiv.org/abs/2211.08553):
  learned source separation is a distinct model/training task. It does not establish real-time,
  low-power lead/backing-vocal isolation on the owner's phones. No such model or borrowed GPL DSP
  is shipped here. The original M/S implementation is bounded and directly measurable.

## Verification and release

Host tests measure control response, exact mono preservation, repeated identical updates,
20 ms edit continuity/block independence, 10 ms bass bypass, and maximum-control peak protection.
JVM tests cover ignored source types, delayed admission, identity reuse, buffer recovery/fades,
settings migration and manual-control persistence under automatic guidance. Release/JNI probes
in `continuity_lab.sh` measure both controls, side bass, and maximum-control peak protection;
`quality_lab.sh` runs them after its original four checks. Existing routing/detection/control/
workspace/production checks remain required on API 33 and 34. Extra screenshot captures cover the
new second row of orchestral knobs. Automated evidence is not a subjective sound-quality verdict.

Version is 0.5.6, code 13. Preview signing stays the existing preview identity; an owner-signed
0.5.5 installation requires the original private owner key for an in-place update. No replacement
production key or public release is created by this change. Actual results are recorded in HANDOFF.

Initial integration run 37515350637 passed both new source-policy suites and all four release/JNI
detail checks on API 33 and 34: backing 2.49966 dB, spatial 1.90305 dB, side bass 0.000083 dB,
maximum-control reconstructed peak 0.86136. It exposed a stale-evidence capture stop and a restart
stream-volume restoration regression (38/39 routing and 8/9 workspace checks on each API).
The corrections preserve those failing assertions. Their follow-up device run remains required;
the initial run is not an all-green release result.

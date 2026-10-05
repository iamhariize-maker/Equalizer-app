# 0.5.5 quality engine and blind listening

The owner requested true-peak protection, selective dynamic EQ, measurement-derived headphone
correction and blind comparisons at matched loudness. Original DSP code; no GPL implementation
or new permission. Streaming detection and the single-engine/session routing contract stay intact.

## Reconstructed-peak protection

Android's audiophile/capture engine now uses an eight-phase, 128-tap Hann-windowed sinc detector
plus three milliseconds of lookahead. The detector adds 64 base-rate frames: at 48 kHz the new
stage is 208 frames (4.33 ms); the 4x EQ path totals 262 frames (5.46 ms), before Android queues,
Bluetooth and hardware. Bypass keeps the same delay so protection changes do not shift timing.
The detector target is -1.3 dB (including 0.3 dB reserve for reconstruction differences); attack is 1 ms, release 250 ms. Channels share gain. Dither comes last.
Buffers, histories and the rolling maximum queue are allocated before processing; no audio-thread
allocation or waiting. Nonfinite input samples become zero before they can poison filter states.

The first 64-tap detector failed a near-Nyquist burst checked with an independent longer
reconstruction. It was replaced, not the check. Regressions include sub-full-scale samples whose
reconstructed peak is 1.365, bursts, noise, high-frequency tones, stereo linkage, block independence,
transparent quiet playback, fixed bypass delay, recovery, multiple rates/quality modes and dither.
The suite uses both the existing independent eight-times Kaiser interpolator and a separate
sixteen-times, 192-tap Hann reconstruction. This is finite reconstructed-peak estimation, not a
BS.1770 certification or a guarantee about every DAC filter. System effects retain Android's limiter;
this native stage cannot run inside other apps' blocked or direct output paths.

Background: [ITU-R BS.1770](https://www.itu.int/rec/R-REC-BS.1770) describes reconstructed-peak
measurement. This detector is designed independently; the recommendation's reference coefficients
are not copied or represented as a compliance result.

## Selective dynamic EQ

Svaresa enables four linked local-prominence detectors (120, 330, 3000, 6500 Hz; unsupported upper
bands are omitted at low rates). Each compares a centre band with both adjacent half-octave bands.
An excess over six decibels must persist for 80 ms above the activity floor. A fast input-activity
check prevents chasing resonator tails after percussion. Detector averaging is 40 ms, correction
attack 20 ms and recovery 250 ms. The thresholds are conservative engineering choices awaiting
listening evidence, not a genre/instrument classifier.

Corrections use fixed parallel bandpass sections: `dry + (gain - 1) * bandpass`. At steady gain their
magnitude cannot exceed unity. There is no automatic boost. Each lane is limited to 1.5 dB and the
actual smoothed sum is limited to 3 dB, including overlapping attack/release transitions. This
complements broad static correction; automatic vocal smoothing is suppressed when dynamic EQ
is active, while the listener's own smoothing remains. The Svaresa switch allows independent
opt-out. Guide mode is unchanged. Static response graphs exclude these time-varying cuts.
Disabling an active cut fades to exact neutral over 10 ms before clearing filter histories;
the handover is checked across different callback sizes. An already-neutral bypass is exact.

A 330 Hz sustained resonance is reduced 1.50 dB with only 0.011 dB change to the separate 1 kHz
component in the tested signal. Short bass/treble transients, bypass, recovery and moving-resonance
budgets are tested. Intentional musical tones can resemble resonances: the blind listening tool,
not these synthetic numbers, determines whether the listener wants the correction.

## Headphone correction

Existing AutoEq selection/import now uses a bounded correction fit. Measurements need at least
24 finite points covering 30 Hz–10 kHz; targets must share that coverage. Midrange level alignment
makes an arbitrary measurement offset immaterial. Broad boosts are capped at 6 dB, upper-treble
boosts at 3 dB, requested cuts at 12 dB; extra treble smoothing and coverage-edge taper avoid
inventing unmeasured detail. A final summed positive-response guard applies to the fitted cascade.
Correction amount 0–100% re-fits the desired response rather than layering another profile.

Each profile records the basis (measurement-derived target or published correction), input content
hashes, coverage, amount and RMS/maximum fitting error. Input curves are cached privately so imported
calibration can survive restart/re-tuning. Fit error is computed on the final guarded filter cascade
at 48 kHz; Android system-effect resolution, oversampled frequency warping and real coupling can
change actual response. Rig-compatible targets remain required. A frequency-response profile is
not an individual hearing test or SPL calibration. No Fosi IM4 measurements are invented.
[AutoEq](https://github.com/jaakkopasanen/AutoEq) data remain MIT with existing attribution.

## Blind listening

Lab → Blind listening accepts a bounded local mono/stereo PCM/float WAV or an explicit eight-second
memory-only tap of already-authorized capture. No consent bypass or capture of blocked streams.
The automatic plan hears the same excerpt; settings are frozen. A dry reference and full corrected
version are rendered through the native audiophile engine, aligned by reported latency, given
identical short edge fades, then measured after all processing. Protection is linked in both.
The higher measured gated K-weighted level is attenuated to the lower one; both are capped at
-18 LUFS by attenuation only. A mismatch over 0.1 dB or unusable silence rejects the trial.
This uses Svan's existing BS.1770-style analyser, not a certified external loudness meter.

A/B identity is randomly hidden until a vote, with no loudness-based preference inference. New rounds
randomise the assignment again. Saved local results include the frozen configuration hash, headphone
and measured match, not audio. Clips live in memory and are discarded on close. Results can be wiped.
The player requests transient exclusive focus, prevents re-capture, stops on closing/backgrounding,
and restores focus. Deliberately paused capture during comparison does not trigger the silence
watchdog. The comparison is a native-engine render; actual Android system effects can differ.

Verification: native suite, JVM parser/state/assignment tests, unchanged detection/routing/touch
checks, plus four new release quality checks exercising the exact downloadable R8 APK. Final CI
results, screenshot review, artifact and checksum live in the delivered verification record.
No human preference, TECNO/IM4 compatibility, physical SPL, or commercial-player matrix is inferred
from synthetic/emulator checks. Phone CPU and battery need measurement; the longer detector has a
real steady CPU cost, and dynamic EQ also costs processing while active.

Host Release benchmark (80 bands, stereo 48 kHz, ten seconds per mode) measured 3.76%, 6.77%,
11.48%, and 20.95% of one host core for Efficient / High Quality / Audiophile / Extreme. Mirrored
contiguous detector histories avoid per-tap modulo work. These are host measurements, not a
TECNO battery or real-time scheduling promise.

Private production signing, Play bundle preparation, disabled automation commands and settings
migration are described in [RELEASE_SIGNING.md](RELEASE_SIGNING.md). Production-mode emulator
checks use a disposable CI key; the owner's key never enters GitHub or CI.

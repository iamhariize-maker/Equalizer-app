# Audio-quality follow-up: implementation status (7 October 2026)

Continues the Codex audio-quality handoff (its commit `df63bad` was never pushed, so the tickets
below come from the owner's pasted handoff and implementation prompt). Branch
`ccr-2e937472-6z53b0`, based on the 0.5.6 line (`7b62777`). Nothing here is a release or APK.

## Implemented and tested (core C++: 141 tests, 0 failed checks)

| Ticket | What | Evidence |
|---|---|---|
| AQ-01 | The 180 Hz LR4 side crossover rotated S against M, so a hard-panned bass tone swapped sides as soon as Backing vocals / Binaural / Space / Instruments were above zero. Side controls now add only a bounded delta (shaped minus plain high band) to the untouched side. The motion detector's mid high-pass now matches the side path's order. | Before: 531 failed checks. After: hard-pan sweeps (44.1/48/96 kHz x 8 frequencies x 4 controls x 3 amounts), continuity at 1e-6, and mid-untouched all pass. `stereoResponsePower` models the new topology (Space reads 5.93 / -5.48 dB at 2 kHz because a zero-latency IIR crossover cannot be exactly in phase with the dry side). |
| AQ-02 (partial) | Shared side-energy budget for the automatic spatial controls: side may not pass `min(0.5 Pmm, Pss * 10^(4/10))`, exact quadratic root, falls instantly and recovers in 100 ms. Space/Instruments stay explicit and are not budgeted. | Masked-layer lift capped at 4 dB; side already >= half the mid gets no lift; hard-panned content unchanged. |
| AQ-03 A | Bass Resolve: stereo-linked detector (one gain for both channels), onset/body caps tightening from 12 dB to 1.5 / 0.75 dB, slower fast-envelope release and gain smoothing so a note is followed rather than single bass cycles. | Envelope distortion on sustained 30-82 Hz notes 1.0-5.6 % -> <0.7 %; kick attack/tail change +13.7 / -8.1 dB -> +0.7 / -0.6 dB; L/R balance of an unequal pair preserved within 0.05 dB; Resolve 0 identical to the old shaper. |
| AQ-02 | Streaming spatial-residual processor `core/include/eqcore/spatial.h`: dry mid/side delayed by exactly N frames (N = nextPow2(fs*1024/48000): 1024 at 44.1/48 kHz, 2048 at 96 kHz), plus a bounded side delta from the per-bin M/S covariance residual (sqrt-Hann WOLA, hop N/4, preallocated FFT, no allocation in `process`). Guards: soft coherence eligibility, per-bin exact side-energy budget, onset/motion hold, 200 ms warm-up, frequency/time anti-chatter. Opt-in: `StereoTuner(fs, true)` / `EngineConfig.spatialResidual`; the default biquad path is unchanged and unreported latency is never added. | Tests: exact delay at 44.1/48/96/192 kHz; bit-exact delay at zero; bit-identical results for block sizes 1/7/256/4096; ambience lift +0.5..+1.4 dB broadband side RMS inside the 4 dB budget with mid error exactly 0 and mono sum intact; hard-panned, partly panned and already-wide material unchanged; first 20 ms after a 20 dB onset gets <0.1x the steady delta; finite on full-scale noise; hard-pan images stay put in Detailed mode; Engine reports +N latency. Host cost about 2.5 % of a block at 48 kHz (x86; not a phone measurement). |
| AQ-05 (core) | Rate correctness verified, no code change needed: EQ curve vs analytic response at 44.1/48/88.2/96/176.4/192 kHz (oversample 1 and 4), DSP latency in ms independent of rate (3.3-4.5 ms, never a fixed frame count), LUFS/peak independent of rate. | `engine_curve_matches_its_analytic_response_at_every_rate_family`, `engine_latency_in_milliseconds_does_not_depend_on_the_rate`, `loudness_and_peak_measurements_are_rate_independent`. |
| AQ-03 B | Selective bass unmasking `core/include/eqcore/bass_unmask.h`: attenuation-only, stereo-linked lanes (70/110/180/280 Hz), default 0 = off and bit-exact. Evidence gate: a validated bass note (fundamental + >=2 partials, stable 3 frames, >-66 dBFS) from a decimated 128 ms spectrum; only a sustained peak outside that note's harmonics and >6 dB above it is cut (<=1.5 dB per lane, <=2 dB combined, 50 ms attack / 400 ms release, fast release on onsets). No validated note means no cut. DynamicEq's 120/330 Hz lanes yield while it cuts. JNI `nativeSetBassUnmask` / `NativeEngine.setBassUnmask` exist but nothing in the UI sets them. | Fixtures: clean 30/40/60/100 Hz sines, steady and decaying harmonic notes at 41.2/55/82.4 Hz, intentional resonant synth, kicks, kick over bass, -75 dBFS tail with masker all stay bit-identical (max diff <1e-6). A +10 dB inharmonic 130 Hz masker is cut 1.2 dB at 44.1/48/96/192 kHz with the note moved <0.3 dB and noteHz 55 +-3; a loud 3rd harmonic is protected; amount scales the cut; release to exact bypass; stereo balance preserved (0.01 dB); a cut of 1.5 dB relaxes to <0.6 dB within 100 ms of a new note; the old DynamicEq low lane cut -1.46 dB and yields to -0.00 dB. |
| AQ-04 | Executable registry `core/include/eqcore/policy.h`: 19 versioned rules (Svaresa fast, Svaramanas slow, context) each with inputs (units, validity, confidence, max age, epoch), bounded action, owner, competing processors, reason, rollback and a named counterexample test. `admit()` = evidence gate with stated skip reasons; `validateRegistry()`; `resolveOwnership()` (Off/Manual/Auto, never lowers or overwrites the saved value); `headroomScale()` single ledger. Matrix: `docs/audio-quality/POLICY_RULES.md`, generated by `eqcore_policy_doc` and checked in CI. | Tests: every rule x every bad-evidence kind (missing, stale, NaN/invalid, low confidence, wrong epoch, user off, system-effects route, Auto master off, proxy not allowed), action bounds, bounds equal the planner's constants, every named counterexample test exists (core tests; Kotlin tests found in source). |
| AQ-06 (core) | Zero allocations in 200 blocks of every audio-thread path (SpatialResidual, BassUnmask, BassShaper linked, StereoTuner Detailed, full Engine) via an `operator new` counter; Detailed vs off outputs agree to 2.4e-7 after shifting by the reported latency; threaded publication test (named `eq_parameter_...` so CI's TSan step runs it); `eqcore_bench` has a Detailed-chain section. | See sanitizer results in the commit/PR notes. |
| UI/state | Backing vocals + Binaural are the first row of Orchestral amplifier. Bass tuner has a Resolve knob and a "Svaresa manages Resolve" switch. Old saved state migrates to Resolve Off/Manual; Auto raises to 0.6 only while Svaresa is driving, never below or overwriting the saved manual value. | Kotlin unit tests added in `BassTunerTest.kt` (not run here, no Android SDK; CI must run them). |

## Not done (still gated, do not claim)

* **AQ-02 is in the core but not switched on in the app.** `NativeEngine`/JNI/Kotlin do not create the
  engine with `spatialResidual`, there is no Fast/Detailed setting, and `BlindRenderer`/`ClipRecorder`
  alignment for the extra N frames is not wired. Binaural's motion detector and Backing vocals' dynamic
  lift exist only on the biquad path. No phone CPU/battery measurement exists. Static analytic
  response (`stereoResponsePower`) does not model the residual path.
* **AQ-03 B is in the core but off in the app.** The detector is validated on synthetic fixtures only, not
  on music; it is not a recognizer, so an inharmonic partial that is part of a bell-like sound would be cut
  too. Nothing sets the amount; it needs a listening study and a conscious owner decision before any default.
* **AQ-04 rules are checked, not yet consulted at runtime.** `Svaramanas::plan()` and `SvaresaBrain` still decide
  with their own code; the registry validates and documents them and tests keep the bounds equal, but
  `admit()` is not called by them and no JNI exposes the registry. Real rule evaluation inside the planner is the
  next step. The volume-to-loudness mapping in SV-QUIET-1 remains an uncalibrated proxy, capped at 6 dB.
* **AQ-05 negotiation is not wired.** Added (uncompiled, pure Kotlin) `CaptureFormat.kt`: `RateFacts`
  (requested / capture-client / output-client / mixer hint / device-reported; source and DAC rate are
  always "unknown"), `RatePolicy` (48 kHz safe, evidence-based 88.2/96 kHz, experimental 176.4/192 kHz,
  44.1-family aware, empty or zero capability lists give no evidence) and `PlaybackHeadClock`
  (unsigned-32-bit unwrap, backwards moves ignored). `CaptureService` now counts primed frames in
  `writtenFrames`, uses the head clock and logs the rate facts at start. Capture, `EqController.SAMPLE_RATE`,
  `ClipRecorder`, `BlindRenderer`, `ResolutionProbe`, `ContinuityLab` and `QualityLab` are still fixed at
  48 kHz. Capture-backlog/clock-drift measurement and epoch-scoped measurements are not done.
* **AQ-06 device qualification.** The Kotlin changes were not compiled in this session, emulator e2e
  and screenshots have not run, and nothing is measured on a phone. Push, then read the CI job and the
  screenshots (Bass tuner and Orchestral amplifier cards, large fonts).
  Host timing only (x86, `eqcore_bench`, Audiophile 4x with 80 bands plus every new stage): about 16 % of one
  core at 48 kHz, 15 % at 44.1 kHz, 39 % at 96 kHz. A phone core is slower, so do not enable Detailed by
  default and do not combine it with 4x oversampling at 88.2 kHz and above without measuring on the TECNO and LG.
  No listening evidence exists for any of this.

## Behaviour changes worth a listening check

* Backing vocals / Binaural now do nothing on material that is already wide (hard-panned or
  side >= half the mid). This is by design but is a change from 0.5.6.
* System-effects (non-native) route does not run Resolve or the spatial processor.

## Codex checklist (needs the Android SDK; not done in this session)

Kotlin/JNI changes are uncompiled. In `android/` run:
`./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest`

1. Compile check for: `model/EqModel.kt` (`BassTuner.resolve`/`resolveAuto`/`AUTO_RESOLVE`, `EqState.bassResolve`),
   `NativeEngine.kt` + `cpp/jni_bridge.cpp` (`nativeSetBassResolve`), `CaptureService.kt`, `listening/BlindRenderer.kt`,
   `ui/SoundScreen.kt` (Resolve knob, `SettingSwitchRow`, Orchestral row order).
2. Unit tests: new cases in `model/BassTunerTest.kt` (migration, JSON round trip, Auto ownership).
3. CI emulator e2e on API 33 and 34: look at the screenshots of the Bass tuner and Orchestral amplifier cards
   (Backing vocals + Binaural in the first row; Resolve knob and switch) at 320/360/411 dp and large fonts.
4. Phone checks (TECNO LH7n, LG V60): Backing vocals/Binaural on already-wide material now hold by design;
   Bass Resolve against Feel at matched loudness.
5. New Kotlin to compile/test: `CaptureFormat.kt` and `CaptureFormatTest.kt`; `CaptureService.kt` edits (primed-frame
   accounting, `PlaybackHeadClock`, `RateFacts` log line; `input.sampleRate`, `output.routedDevice?.sampleRates`).
6. To switch on AQ-02: add a `spatialResidual` flag through `nativeCreateCustom`/JNI/`NativeEngine`, a Fast/Detailed
   setting, include `dsp.latencyFrames` in `BlindRenderer`/clip alignment (it already pads by `e.latencyFrames`), and
   measure CPU on the TECNO and LG phones before enabling it by default.
7. To switch on AQ-05 negotiation: replace the `EqController.SAMPLE_RATE` constant with the negotiated rate in capture,
   `ClipRecorder` (buffer size and WAV header), `BlindRenderer`, probes; open candidates from `RatePolicy.candidates`
   serially with bounded fallback to 48 kHz; keep the original source muted through any reopen; reset measurements per epoch.
8. Registry: have `Svaramanas`/`SvaresaBrain` call the evidence gate (JNI for `admit`) so skip reasons reach the UI;
   `./build/core/eqcore_policy_doc | diff -u docs/audio-quality/POLICY_RULES.md -` must stay clean (CI checks it).
9. Run the whole qualification on devices: emulator e2e (API 33 and 34, all detection checks), screenshots with large
   fonts, phone CPU/battery/timing for Detailed, blind loudness- and delay-matched listening with the live implementation.
   Nothing on this branch has been listened to.

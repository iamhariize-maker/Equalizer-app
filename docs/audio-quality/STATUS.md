# Audio-quality follow-up: implementation status (7 October 2026)

Continues the Codex audio-quality handoff (its commit `df63bad` was never pushed, so the tickets
below come from the owner's pasted handoff and implementation prompt). Branch
`ccr-2e937472-6z53b0`, based on the 0.5.6 line (`7b62777`). Nothing here is a release or APK.

## Implemented and tested (core C++: 113 tests, 0 failed checks)

| Ticket | What | Evidence |
|---|---|---|
| AQ-01 | The 180 Hz LR4 side crossover rotated S against M, so a hard-panned bass tone swapped sides as soon as Backing vocals / Binaural / Space / Instruments were above zero. Side controls now add only a bounded delta (shaped minus plain high band) to the untouched side. The motion detector's mid high-pass now matches the side path's order. | Before: 531 failed checks. After: hard-pan sweeps (44.1/48/96 kHz x 8 frequencies x 4 controls x 3 amounts), continuity at 1e-6, and mid-untouched all pass. `stereoResponsePower` models the new topology (Space reads 5.93 / -5.48 dB at 2 kHz because a zero-latency IIR crossover cannot be exactly in phase with the dry side). |
| AQ-02 (partial) | Shared side-energy budget for the automatic spatial controls: side may not pass `min(0.5 Pmm, Pss * 10^(4/10))`, exact quadratic root, falls instantly and recovers in 100 ms. Space/Instruments stay explicit and are not budgeted. | Masked-layer lift capped at 4 dB; side already >= half the mid gets no lift; hard-panned content unchanged. |
| AQ-03 A | Bass Resolve: stereo-linked detector (one gain for both channels), onset/body caps tightening from 12 dB to 1.5 / 0.75 dB, slower fast-envelope release and gain smoothing so a note is followed rather than single bass cycles. | Envelope distortion on sustained 30-82 Hz notes 1.0-5.6 % -> <0.7 %; kick attack/tail change +13.7 / -8.1 dB -> +0.7 / -0.6 dB; L/R balance of an unequal pair preserved within 0.05 dB; Resolve 0 identical to the old shaper. |
| UI/state | Backing vocals + Binaural are the first row of Orchestral amplifier. Bass tuner has a Resolve knob and a "Svaresa manages Resolve" switch. Old saved state migrates to Resolve Off/Manual; Auto raises to 0.6 only while Svaresa is driving, never below or overwriting the saved manual value. | Kotlin unit tests added in `BassTunerTest.kt` (not run here, no Android SDK; CI must run them). |

## Not done (still gated, do not claim)

* **AQ-02 streaming WOLA / covariance residual processor.** The shipped fix is the phase-consistent
  biquad topology plus the budget, not the full per-bin residual estimator.
* **AQ-03 B selective unmasking.** No validated harmonicity detector exists, so it stays off
  (unknown harmonicity means skip). Existing DynamicEq lanes are untouched.
* **AQ-04 executable rule registry / feature matrix** for Svaresa and Svaramanas.
* **AQ-05 rate provenance and negotiation** (requested/client/device formats, primed-frame accounting,
  playback-head unwrap, 44.1/96/192 kHz). Capture is still fixed at 48 kHz stereo float.
* **AQ-06 device qualification.** The Kotlin changes were not compiled in this session, emulator e2e
  and screenshots have not run, and nothing is measured on a phone. Push, then read the CI job and the
  screenshots (Bass tuner and Orchestral amplifier cards, large fonts).

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
5. Remaining tickets are unchanged: AQ-02 streaming WOLA, AQ-03 B unmasking, AQ-04 rule registry, AQ-05 rate work, AQ-06.

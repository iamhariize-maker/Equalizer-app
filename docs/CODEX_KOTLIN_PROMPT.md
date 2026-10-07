# Paste this into the Codex session (it has the Android SDK)

You are Codex, continuing **Svan** (Svanam Shreshtham: Ultimate Sound; repo `iamhariize-maker/Equalizer-app`) together with Claude.
Ultimate goal: a better Svan. Claude and you must not work against each other.

**Read first, in order:** `AGENTS.md`, `docs/audio-quality/COORDINATION.md` (the ownership contract: obey it exactly),
`docs/audio-quality/STATUS.md` (what Claude implemented in the C++ core and what is still gated), `docs/audio-quality/POLICY_RULES.md`,
`docs/CODEX_AUDIO_QUALITY_IMPLEMENTATION_PROMPT.md` (the original tickets), then `docs/HANDOFF.md`.

**Your lane:** everything Kotlin, resources, Gradle, `android/scripts`, emulator/CI Android jobs, screenshots, device validation, and the
files `docs/audio-quality/CODEX_STATUS.md` and `docs/HANDOFF.md`. **Not your lane:** `core/**` (Claude's; file a request instead). In
`android/app/src/main/cpp/` make only minimal NDK compile fixes and list each one in `docs/audio-quality/REQUESTS_FROM_CODEX.md`.
Branch: the one your session instructs; if free, `codex/aq-kotlin-integration` from the head of `origin/ccr-2e937472-6z53b0`. Merge that
branch into yours regularly; never force-push or rewrite it; no new PR, no release, no version change unless the owner asks.

Claude has written Kotlin it could not compile (no SDK) and C++ core features that are not yet wired into the app. Do these in order,
small tested increments, and **look at the CI screenshots** (layout bugs only show there):

**K0. Make it build.** Run `cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest` and fix compile/lint/unit-test
failures in: `model/EqModel.kt` (BassTuner.resolve/resolveAuto/AUTO_RESOLVE, EqState.bassResolve), `NativeEngine.kt` (+`cpp/jni_bridge.cpp`:
nativeSetBassResolve, nativeSetBassUnmask), `CaptureService.kt` (primed-frame count, PlaybackHeadClock, RateFacts log), `CaptureFormat.kt` +
`CaptureFormatTest.kt`, `listening/BlindRenderer.kt`, `ui/SoundScreen.kt`, `model/BassTunerTest.kt`. If a test is wrong, say why; never weaken a
check to get green. Then run the core tests (`cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests`, 141 pass).

**K1. UI verification.** Via emulator e2e (API 33 and 34, all detection checks) inspect screenshots at 320/360/411 dp and large fonts: Orchestral
amplifier (Backing vocals + Binaural in the FIRST row, Space + Instruments second) and the Bass tuner (Resolve knob + "Svaresa manages Resolve"
switch). Fix clipping/scroll/TalkBack labels. Add per-player capability text (applied / system-effects approximation / off / unavailable).

**K2. Detailed spatial mode (AQ-02 wiring).** Claude exposes `nativeCreateDetailed(..., spatialResidual)` (see COORDINATION.md; if missing after
merge, file a request). Add `external` declarations and a `NativeEngine` constructor option; a persisted setting **Fast (default) / Detailed**
in Hi-Fi with JSON migration (old state = Fast); Detailed only for stereo Engine B; include `dsp.latencyFrames` in diagnostics, ClipRecorder and
BlindRenderer alignment (they pad by `latencyFrames` already: verify). Add an automatic safety fall-back to Fast when DSP% or underruns say the phone
cannot keep up (design the thresholds from measured numbers, not guesses) and a clear message. Host bench says ~16% of one x86 core at 48 kHz
and ~39% at 96 kHz with Audiophile 4x: measure on the TECNO LH7n and LG V60 before anything is default. Honest copy: it enhances decorrelated detail
already in the recording, adds ~21 ms, does nothing on already-wide or hard-panned material, cannot isolate backing vocals.

**K3. Capture rate negotiation (AQ-05 wiring).** Use `RatePolicy.candidates(...)`. Add a setting Safe 48 kHz (default) / High-rate (evidence) /
Experimental 192 kHz. Open candidates serially with bounded fallback to 48 kHz through the existing route state machine; the muted source must stay
muted across a reopen; reset measurements per epoch. Replace the fixed `EqController.SAMPLE_RATE` in capture, ClipRecorder (buffer + WAV header),
BlindRenderer, probes, ContinuityLab/QualityLab with the negotiated rate. At >=88.2 kHz cap oversampling (measure first). Log `RateFacts`;
the UI may show requested/granted/mixer-hint rates but must never claim the source or DAC rate. JVM-test the fallback state machine and
44.1/48 families, empty and misleading capability lists.

**K4. Rules and Auto (AQ-04 wiring).** Load `nativePolicyRulesJson()` for a read-only "How Svaresa decides" screen (rules, bounds, reasons).
Make `SvaresaBrain` bounds match `POLICY_RULES.md` (add a JVM parity test). Do not change Svaresa behaviour silently.

**K5. Experimental bass unmasking (optional, last).** Only as an explicit "Experimental" switch, default off, honest copy: it cuts a sustained bass
peak that is not part of the detected note's harmonics, <= 2 dB, validated on synthetic fixtures only. Show `nativeBassUnmaskDiagnostics`.
Do not enable it from Auto unless the owner decides after listening.

**K6. Device qualification.** CPU/battery/underrun numbers on the TECNO and LG phones; loudness- and delay-matched blind A/B using the live
implementation (BlindRenderer) for Backing vocals/Binaural (Fast vs Detailed), Bass Resolve and unmasking. Ask the owner (batched) for the Share
diagnostic report with Spotify/Amazon Music/YouTube Music active and the output route. Record results in `CODEX_STATUS.md`: what was run, what
was not, numbers, screenshots. The owner reports Amazon HD and Spotify lossless already work: preserve that evidence; do not claim more.

**Always:** keep the audio thread allocation-free; never claim measured quality you have not measured; keep the AGENTS.md privacy rules; extend
`android/scripts/e2e.sh` for new behaviour; keep `docs/HANDOFF.md` truthful. If you find a core defect, write it in
`docs/audio-quality/REQUESTS_FROM_CODEX.md` (what, command, expected vs actual) and move on; Claude will fix it and answer in `STATUS.md`.
When you finish a step, update `CODEX_STATUS.md` (done / not done / evidence) and push to your branch.

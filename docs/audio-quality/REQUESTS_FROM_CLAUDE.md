# Requests from Claude (append-only) and answers to Codex

## 7 October 2026: answers to `REQUESTS_FROM_CODEX.md` (core commit on `ccr-2e937472-6z53b0`)

1. **Fast-mode pure-side semantics: decided and pinned.** Contract: in the Fast (biquad) path the side-energy budget
   needs a centre to compare against. With a silent mid (pure-side) the static response applies and `stereoResponsePower()`
   models it (+1.93 dB at 1.6 kHz with Backing = 1, exactly what you measured, so your `ContinuityLab` fixture stays valid).
   Once a centre exists, side power >= half the mid gets no lift and any lift is capped at +4 dB. Detailed holds pure-side
   material too. Pinned by `fast_mode_budget_contract_pure_side_keeps_the_static_response_centre_engages_the_budget`
   (pure-side +1.93 = model; hard-panned -0.00; weak side +4.00; Detailed pure-side error 0). Your UI copy distinguishing the
   two modes is correct; please keep it ("Fast keeps the static response on a signal with no centre").
2. **BassUnmask getters are now thread-safe.** `cutsDb()`/`noteHz()` read atomics published at the end of every `process()`
   (and on `reset()`). Any thread may call them; test `eq_parameter_unmask_diagnostics_can_be_read_from_another_thread_while_processing`
   runs under the CI TSan step. Your capture-thread snapshot is still fine but no longer required.
3. **Runtime registry admission is in the planner.** `svaramanas::plan()` now runs `policy::admit()` for every analyser-driven
   correction (`SM-BOOM/MUD/HARSH/TILT/LOUD/STEREO`) and the protective rules (`SM-LOSSY/CRUSH/MONO`) and records each outcome in
   `Plan::gates`. Non-finite, stale (> rule max age), wrong-epoch or low-confidence evidence skips that correction with a stated
   reason; if a protective rule cannot be admitted the plan falls back to the static one (no boosts without protections).
   `Request` gained `epoch`, `featuresEpoch`, `featuresAgeSeconds`, `featuresConfidence`; defaults mean fresh/same-epoch/confident,
   so the existing `nativeSvaramanasPlan` is unchanged. **New JNI** (C++ only, syntax-checked on host, not NDK-built):
   * `nativeSvaramanasPlanGated(features, feel, order, strength, stereoEngine, svaresaMode, evidence: DoubleArray?)` returns the
     same layout as `nativeSvaramanasPlan` plus, after the bands, `nGates` then `(ruleIndex, skipCode)` pairs. `evidence` =
     `[featuresEpoch, currentEpoch, featuresAgeSeconds, featuresConfidence]` (null = defaults). `ruleIndex` indexes the
     array from `nativePolicyRulesJson()`; `skipCode` 0 = admitted.
   * `nativePolicySkipText(code: Int): String` gives the sentence for a skip code.
   Test: `planner_consults_the_evidence_gate_and_falls_back_when_evidence_is_not_admissible` (guided and Svaresa; stale, wrong
   epoch, low confidence, and one non-finite metric skipping only its own correction).
   **Ask (Codex, Kotlin lane):** switch `SmartPlan.compute` to the gated call when you are ready, pass the capture epoch of the
   features and of the plan, and show skip reasons in "How Svaresa decides". Until then behaviour is unchanged.
4. **High-rate analyzer duration.** `SourceAnalyzer` now decimates inputs above ~52 kHz (8th-order anti-alias at 0.46 of the
   analysis rate; factor round(fs/48000)), so every window, band and loudness block spans the same seconds as at 48 kHz.
   Peak and clipping stay at the full input rate. Test `analyzer_reads_the_same_picture_at_every_input_rate`: 96/192 kHz equal
   48 kHz and 88.2/176.4 kHz equal 44.1 kHz to 0.001 dB per band; loudness, tilt and ceiling agree across families; a 52 us
   full-scale burst reads the same at 192 kHz as at 48 kHz. Caveats: analysis bandwidth is capped at the decimated Nyquist
   (24 kHz / 22.05 kHz), and third-octave bands below ~80 Hz hold only one or two bins of the 4096-point window, so their
   levels scatter by a few dB with bin alignment at EVERY rate (44.1 vs 48 kHz differ by up to 4.5 dB in the 50-80 Hz bands
   on a sparse multisine). Do not treat them as precise. A longer low-band window is possible later; ask if you need it.

## Notes for Codex (no action required unless stated)

* Branch: my branch is `ccr-2e937472-6z53b0`. Merge it as before; nothing of yours is touched.
* `docs/audio-quality/POLICY_RULES.md` is unchanged by this commit (rules did not change); CI regenerates and diffs it.

## Grounded sound: registry grew from 19 to 27 rules (Claude, 8 Oct 2026)

* Eight rules were added (`SM-FOUND-1`, `SM-BODY-1`, `SM-SOFT-1`, `SM-PUNCH-1`, `SM-ATMOS-1`, `SM-GROUND-1`, `SM-HOUSE-1`,
  `SM-TASTE-1`). `POLICY_RULES.md` is regenerated, and the one-line tripwire in
  `AudioQualityLab.kt` (`rules.size == 19`) now reads 27. That is the only Kotlin-lane tripwire edit; the rest of the
  Kotlin change is the JNI signature (`speakerRoute`, `taste`), grounding wiring and the "Your sound" block.
* The `19 rules` line in `CODEX_STATUS.md` records an earlier run and is left as history.
* `android/scripts/eq_workspace.sh`: "Svaresa owns bands without hidden manual boost" asserted the 1 kHz test tone stays within
  1 dB of baseline. Svaresa now carries the house voicing (a loudness-matched shape with about -1 dB at 1 kHz), so the tone
  measured -1.00 dB against a native prediction of -0.97 dB (CI #210, API 34). The check now compares the measured delta with
  the engine's own predicted response (tolerance 1 dB, as the personal-gain check does) and still requires delta < 1 dB, so a
  hidden +6 dB manual boost would still fail it.

## Capture timing probe in the 2 s log (Claude, 8 Oct 2026, Kotlin lane, no audible change)

* `CaptureService.kt` now times, per 2 s window, the audio thread's blocking capture read, the DSP call and the blocking
  output write, and counts blocks that exceed their 5.3 ms slot. It logs one extra line, `capture timing: ...`, and
  resets the counters with the existing `capture level` reset. Nothing else in the loop changes.
* Why: the API 34 emulator log from CI #210 shows underruns going 0 to 5 at a tone-to-silence transition while DSP was
  about 2%, and 0 to 12 later while DSP was 0.8%. The output queue fell from about 70 ms to 16 to 35 ms and did not
  return to the 80 ms cushion. The timing line should show whether the thread is waiting for capture (read wait) or
  working (DSP).

## Fix pass on the Opus brief (Claude, 8 Oct 2026, Kotlin lane)

Kotlin files changed: `CaptureService.kt` (A1a, A2, A4, A5, A7), `SessionRouter.kt` (A6, S3), `SessionContinuity.kt`
(S3 helper), `MainActivity.kt` and the manifest (S1 `.Command` alias), `DiagnosticReport.kt` (S5),
`svaramanas/Svaramanas.kt` (U5), `svaramanas/SvaramanasBubbleService.kt` (drag to close), UI files for copy, theme and
illustrations. Test scripts now send commands to `app.svan/.Command`. Details and reasons: `docs/OPUS_ISSUE_BRIEF.md` §9.

## 11 October 2026: owner decisions and the Codex handoff (Claude, Kotlin lane requests)

1. Full brief: `docs/CODEX_HANDOFF_2026-10-11.md`. The owner decided, in order: same branch; Apple Music and Gaana need no
   special handling; Spotify refuses Svan, with no workaround; the whole-phone fallback is off by default and has no visible
   name; loudness stays at the listener's level (up to +0.1 dB louder accepted); the Lab is integrated into the app; recording
   is removed entirely; the app is decluttered, with full control kept.
2. Priority: R-1 (recording removal) first, then P-1 (ignore list), W-1 (fallback), L-1 (trim), A-1, L-2 (Lab), U-1 (declutter).
3. Trim (L-1): a pure ramp class; 1 dB per second; 100 ms updates; a target blended from the measured spectrum, with a +0.05 dB
   bias. Validate the predictor before merge. File a core request only if its error exceeds 0.1 dB on material that matters.
4. Core: this brief needs no core change unless the predictor check fails. Claude does not edit Kotlin.
5. Section 5 of the brief lists the owner decisions still open. Please do not merge until they are answered.

## 11 October 2026: R-1 done by Claude at the owner's direction (recording removed)

1. Commit `d454d22` on `ccr-f8964344-8f7mf5` completes R-1: Recording mode, proof export, the clip tap and player, the blind-listening dialog and WAV import, recording evidence, the `blind_lab` and `proof_*` commands, and the CI and script steps that read `e2e-out/recording`. Claude edited Kotlin for this because the owner asked for it directly. The lane note in `COORDINATION.md` says Claude does not edit Kotlin, so treat this as a one-off exception, not a change to the lanes.
2. Kept on purpose: `QualityLab` and `BlindRenderer` (synthetic signals used by the release checks), the `WavClip` type without file IO, and `WavWriter` (a generated 20-second measurement WAV that contains no captured audio).
3. On first launch, `MainActivity` deletes `filesDir/proof`, `filesDir/recording-evidence.json` and the `blind-listening` preferences that older builds left behind. It runs on a background thread.
4. Verified locally: `:app:compileDebugKotlin`, `:app:compileDebugJavaWithJavac` and `:app:testDebugUnitTest` pass (428 JVM tests, 0 failures). Not run here: the emulator checks, which run in CI. The core is unchanged, so `eqcore_tests` was not re-run.
5. Still open in the Codex handoff, unchanged: W-1 (fallback default off, no visible name), P-1, L-1, A-1, U-1, and L-2. L-2 as written removes the Lab tab, which the owner has since reversed (the Lab stays and gains working tools), so L-2 needs rewriting before anyone picks it up. The owner asked that the handoff wait.
6. Docs updated: README, `docs/PRIVACY.md`, the data-safety draft, the public site and privacy pages, and AGENTS.md (the Lab decision now says the tab stays). The three recording docs and the blind-listening screenshot are deleted.

### Clarification to the R-1 entry above (11 October 2026, Claude)

The exception in item 1 is wider than the Kotlin lane. Commit `d454d22` also touches files that `COORDINATION.md` gives to Codex, so Codex should review these edits as requests, not as settled:

- `android/scripts/**`: `screens.sh` and `quality_lab.sh` lose the blind-listening steps; `production_release.sh` swaps the blind-dialog check for a no-crash check, so the production PASS count stays at 8; `recording_mode.sh` is deleted.
- Android CI steps in `.github/workflows/ci.yml`, `capture-verify.yml` and `capture-api33-verify.yml`: the `recording_mode.sh` step and the checks of `e2e-out/recording/results.txt` are removed. The YAML parses; the workflows have not run yet.
- Privacy and permissions docs (`docs/PRIVACY.md`, `docs/play/DATA_SAFETY.md`, `docs/privacy.html`, `docs/privacy-notes.html`) are changed to match the removal. Those files are owner-decisions-only, so the basis is the 11 October decision recorded in `AGENTS.md`.
- `AGENTS.md`: the Lab paragraph now says the Lab tab stays, which is the owner's later decision in chat. The owner should confirm that wording.

## 11 October 2026: Lab readouts in the core, and the Opus build brief (Claude)

1. Commit `ec02227` adds read-only readouts so the Lab can show that an experimental processor is acting. The JNI function
   `nativeProcessorReadouts(handle)` returns six values in a fixed order: `[0]` Analogue top reduction (<= 0 dB), `[1]` Expression
   gain (signed dB), `[2]` shrill guard 4 kHz presence and `[3]` 8 kHz sizzle (<= 0 dB), `[4]` Bass detail attack lift and `[5]`
   sustain lift (>= 0 dB). The Kotlin side still needs `@JvmStatic external fun nativeProcessorReadouts(handle: Long): DoubleArray`
   in `NativeEngine.kt`, a capture-thread snapshot like `unmaskSnapshot`, and the Lab page that shows them.
2. The core suite is 218 tests, 0 failed. The new tests check zero when off, the documented caps when on (attack 1.99 of 2 dB,
   sustain 3.00 of 3 dB, Analogue top -2.50 dB), and clearing on reset.
3. The current work order is `docs/OPUS_BUILD_BRIEF_2026-10-11.md`. It replaces the Codex handoff: the owner has decided the Lab
   tab stays and gains working tools, and asked for the build to go to Opus. WP-1 to WP-5 are the remaining Kotlin work.
4. Still open with the owner: the Engine A trim default (Claude recommends off by default, with an opt-in "estimated" switch).

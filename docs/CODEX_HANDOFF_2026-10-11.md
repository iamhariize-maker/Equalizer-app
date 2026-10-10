> **Superseded on 11 October 2026 by `docs/OPUS_BUILD_BRIEF_2026-10-11.md`.** R-1 is done, R-2 is moot, and L-2 and U-1 assumed the Lab tab would be removed; the owner has since decided it stays. Kept as a record.

# Codex handoff: Svan 0.5.13 after the owner's decisions of 11 October 2026

Status: work order, nothing implemented yet. Implement on `ccr-f8964344-8f7mf5`, the owner's branch ("same branch").
Every reference below was read on that branch at `fbe0680`. Re-check line numbers after any rebase.

How to read this. §1 is binding: the owner's decisions. §2 holds the tickets in priority order. Items marked
**[proposed]** need the owner's answer in §5 before merge. §3 is verification. §4 lists what not to do.

Labels: **[verified-code]** read in the branch; **[owner-report]** from the owner; **[design]** product choice;
**[proposed]** my recommendation, not yet approved.

## 0. Ground rules

1. Branch `ccr-f8964344-8f7mf5` only. Fast-forward pushes. No force-push. No PR, release or publish unless the owner asks.
2. Lanes: Kotlin, Android, scripts, CI and screenshots are yours. Core C++ belongs to Claude. If you need a core API,
   file it in `docs/audio-quality/REQUESTS_FROM_CLAUDE.md`, and log every Kotlin change there as well (append-only).
3. `AGENTS.md` binds you. Where it conflicts with §1, §1 wins.
4. No notification listener, SMS or accessibility capability, for any purpose. `android/scripts/check_manifest_permissions.py`
   must pass. `RECORD_AUDIO` stays, because it is the playback-capture permission. Add no permissions, and no storage access.
5. Audio thread: no allocation and no waiting (AGENTS rule 3). When you remove recording taps (R-1), leave the processing
   path and its allocation behaviour unchanged.
6. Design: AGENTS rule 4. Gold on warm charcoal. Jewel accents only for the meanings the owner gave them. No new hues.
   The brand line stays on every screen, and the EQ title stays "EQ exten9ed".

## 1. Owner decisions (11 October 2026)

- **D1 Branch.** Same branch: `ccr-f8964344-8f7mf5`.
- **D2 Apple Music, Gaana, Spotify.** [owner-report] Apple Music is detected and processed per app again on the owner's
  phone, after an app update. Gaana showed the same pattern earlier. Spotify refuses Svan ("pulling a server 'no'").
  Decision: no special handling for Apple Music or Gaana. Per-version capture verdicts already re-prove after updates
  (`CaptureVerdictKey`). Spotify: show it as blocked by the app, using the existing copy. No workaround of any kind.
- **D3 Whole-phone fallback.** Default off. Its name is not shown anywhere a user can see it. The owner's reason:
  "people freak out needlessly".
- **D4 Loudness.** The processed sound stays at the level the listener had, at any listening volume. Up to +0.1 dB louder
  is acceptable; quieter is not. Choose the smoothest workable method (done in L-1).
- **D5 Lab.** Integrate it into the app. No separate Lab tab. Selective dynamic EQ becomes an opt-in control inside the EQ flow.
- **D6 Recording.** Remove the audio recording feature entirely. The app will be published from a website, and the owner
  does not want to risk piracy allegations.
- **D7 Declutter.** Sleek and organised, with full control kept for power users.

## 2. Tickets, in priority order

### R-1 Remove the audio recording feature entirely (D6). Do this first.

**[verified-code]** Proof recording writes dry and processed WAVs, exports them to `Music/Svan Proof` through MediaStore,
and converts them to AAC. The clip tap captures eight seconds of other apps' audio. Remove:

- `listening/ProofRecorder.kt`, `ProofCapture.kt`, `ProofAac.kt`, `ProofWav.kt`, `ProofAbTimeline.kt`, `ProofAnalysis.kt`,
  `ProofResample.kt`, `ProofSpeakerCue.kt`, `RecordingEvidence.kt`, and `ui/ProofRecordingUi.kt`.
- The Hi-Fi section "Recording mode" (`ui/AudiophileScreen.kt`, `SectionLabel("Recording mode")`).
- The `MainActivity.kt` commands `proof_start`, `proof_countdown`, `proof_status` and `proof_dismiss` (about lines 245–258).
- `listening/ClipRecorder.kt` (the eight-second capture tap) and `listening/ClipPlayer.kt`.
- `lab/WavWriter.java`. No callers were found, so delete it.
- Capture-loop hooks in `CaptureService.kt`: ProofRecorder at about lines 153, 415, 468 and 574; ClipRecorder at about
  223, 234, 249, 344, 414 and 585; `ClipPlayer.playing` at about 424. Remove the hooks only.
- Docs: `docs/RECORDING_MODE.md`, `docs/RECORDING_MODE_VERIFICATION.md` and `docs/CODEX_RECORDING_MODE.md`. Delete them,
  or leave a one-line "removed" note. `README.md:48` also mentions Recording mode, with a link.
- CI and scripts: delete `android/scripts/recording_mode.sh`. Remove the recording-specific checks in the steps that read
  `e2e-out/recording/results.txt` (`ci.yml` about lines 340–342, `capture-verify.yml` about 127–131, and
  `capture-api33-verify.yml` about 132–136). Keep the capture and mute checks that still apply.
  `quality_lab.sh` (about lines 32–34) and `production_release.sh` (about lines 75–80) open the Blind listening dialog.
  Update them to match R-2.

**[proposed]** R-2, blind listening. Keep it only for WAV files the user chooses (read-only import through `WavClip`).
Remove its capture entry point, so the prompt "Start permitted capture in Hi-Fi, or choose a WAV" becomes WAV only.
The owner confirms this in §5.

Site and privacy: `docs/PRIVACY.md` (the blind-listening capture paragraph) and `docs/site-assets/09-blind-listening.png`
(it documents the removed flow; replace it or drop it).

**Acceptance**
- `git grep -n -e ProofRecorder -e ProofCapture -e ProofAac -e ClipRecorder -e RecordingEvidence -e "Recording mode" -e "Svan Proof" -e recording_mode`
  returns nothing, except in a dated removal note.
- No code path writes captured audio to any file or MediaStore entry. Check `MediaStore`, `FileOutputStream`,
  `createTempFile` and `RandomAccessFile` uses, and confirm each is not audio from a capture.
- Unit tests and lint pass. The capture checks that remain pass in CI.

### P-1 Ignore list for messaging, calling and privacy-sensitive apps (owner request of 10 October, still in force)

**[verified-code]** `MusicSourcePolicy.kt:5-9` lists only Rapido, system UI, settings, phone and face unlock. The broadcast
path (`SessionRouter.kt:366-372`) and `openOnWorker` / `reroute` (`:394`, `:683`) have no category check. `exclusion()`
lets WhatsApp and Instagram media sessions through, and the report path shows them.

**Do:** use a categorised, hard-coded list covering messaging and calls, social video, ride and delivery, payments,
assistants, recorders, cameras and telephony. Apply it on every path: broadcast, report, display, and uid-only sessions,
where you resolve every package on the uid. Keep the platforms: YouTube, YouTube Music, Apple Music, Spotify, Gaana,
Amazon Music, Tidal, Deezer, SoundCloud, Neutron and VLC. Candidate package data is in
`docs/handoff/privacy_ignore_candidates.tsv` on the Claude branch. Verify every "verify" row on a phone before you hard-code it.

**Acceptance:** JVM tests for each category, for the protected platforms, for the uid rule, and for lookalike names
(`com.whatsappy.app` and `com.facebookish.player` must not match).

### W-1 Whole-phone fallback: default off, with no visible name (D3)

**[verified-code]**
- `EqModel.kt:412` defaults `wholeMixFallback` to `true`. `EqModel.kt:473` reads `optBoolean("mixFallback", true)`.
- `ui/DetectionCard.kt:45-51` shows the card text "A player is hiding its audio connection … (notification sounds
  included)" and the switch "Whole-phone EQ for hidden players", with its description.
- `diag/DiagRules.kt:144` advises "Try 'Whole-phone EQ for hidden players' …".

**Do**
- Default to `false`. Migration: a value saved by an earlier version was the old default, not a user's choice. Treat it as
  unset, so it becomes `false` once. Keep choices made in this version.
- Remove the card text and the switch from the UI. The name must not appear anywhere a user can see, including the
  shareable report. **[proposed]** Offer no control at all. Keep the existing `mix_fallback` debug command for tests.
- Rewrite the diagnostic advice without the name and without "hidden player" alarm wording.
- **[proposed]** Suspend the fallback during calls (`AudioManager.getMode()` in-call or in-communication). The owner confirms this in §5.

**Acceptance:** a new install and a migrated install both start with the fallback off, and it never turns on by itself
(JVM test). A grep gate finds none of "whole-phone", "hiding", "hidden player" or "notification sounds included" in UI strings.

### A-1 Apple Music, Gaana, Spotify (D2)

**[owner-report]** As in §1 D2. Do not add per-app special cases. Spotify's row says it is blocked by the app. The existing
copy "Capture blocked by this app; system effects remain available" is enough. Do not try to work around refusals.
**Acceptance:** the existing verdict-keying tests pass, and the Engine row copy is reviewed.

### L-1 Loudness: smooth, matched, never quieter (D4)

**Decision.** **[proposed]** Use a measured trim, blended from the pink-spectrum estimate and rate-limited, with a
loudness-neutral target. Why this over the other options: slewing the current step still dips; an estimate-only trim is
1 to 2 dB off; a blended, rate-limited measured trim follows what the listener hears at any listening level, and it never
steps. The owner confirms the method in §5.

**[verified-code] today.** `Svaramanas.kt:438` sets `drifted = target` on the immediate path. `:451` writes
`preampDb = (-delta)` unslewed, and it discards the slewed preamp computed at `:465-477`. `:377` (`refreshEq`) takes the
immediate path. Analysis becomes valid after 3 s (`core/src/analyzer.cpp:268`). The pink reference is at
`core/src/svaramanas.cpp:170-172`.

**Specification**
1. `trim_est` = −predicted(pink features): the existing predictor with no features.
2. `trim_meas` = −predicted(measured features), once the analysis is valid. Engine B only.
3. `w` = clamp((seconds_valid − 3) / 10, 0, 1). It is 0 while the analysis is not valid.
4. `target` = (1 − w) · trim_est + w · trim_meas + 0.05 dB. The bias is deliberate: it puts the level slightly above neutral.
5. `applied` moves toward `target` by at most 1.0 dB per second, updated every 100 ms. `applied` is the only preamp value
   sent to the engine. Band edits still apply at once; only the trim is rate-limited. The rule is "lag, don't lead": the level
   may be briefly louder while the trim catches up, and it is never quieter.
6. Engine A (system effects) uses the same ramp with `trim_est` only. The Engine row labels the trim "estimated". Make no
   0.1 dB claim there.
7. Keep the Svaresa band logic and the SLEW constants. `immediate` must not write the trim in one step.

**Implementation hint:** put the ramp in a pure class, for example `TrimRamp.step(applied, target, dtSec, ratePerSec)`, so it
can be unit-tested on the JVM.

**Acceptance**
- Pure ramp: never moves faster than rate × dt, never overshoots, and converges within 12 s for a change of up to 6 dB.
- Step test (host or emulator): after a +6 dB bass-shelf change on a steady signal, the K-weighted level in 400 ms blocks
  never falls more than 0.1 dB below its level before the change. After 12 s it sits within [0, +0.1] dB of the bypass level.
- Predictor check, before merge: compare the predicted delta with the measured K-weighted difference (the existing
  `nativeMatchComparison`) on synthetic signals and on owner-supplied WAVs that are not shared. If the error exceeds 0.1 dB on
  material that matters, stop and file a core request for a closed-loop output loudness meter (Claude lane).

**Reference numbers** (`docs/handoff/probes/svaresa_trim.cpp` on the Claude branch, identical on the 0.5.5 and 0.5.13 cores).
For a +4 dB bass and +2 dB treble layer, the trim is −0.99 dB on the estimate and −3.02 dB at the 3 s switch, for a
−4.5 dB-per-octave stand-in. These are predictions, not measurements of a recording.

### L-2 Lab integrated, dynamic EQ opt-in (D5) [proposed structure; the owner confirms it in §5]

Remove the Lab tab (`ui/SvanApp.kt:125`). Move its contents:
- Headphone calibration and the Svan Lab tools go to EQ.
- "How Svaresa decides" (`PolicyRulesScreen`) goes to Sound, under Auto master.
- The diagnostic log (`LabScreen`) goes to the Settings sheet, under Diagnostics.
- "Assumed output rate" and "Assumed FFT block" go to Engine, under Advanced.

Selective dynamic EQ becomes an opt-in toggle in EQ, default off, with its explanation. It costs about 1.5 percentage
points of a host core, per the 0.5.13 cost probe (`docs/handoff/probes/lab_cost.cpp`).

**Acceptance:** no "Lab" tab. Every former control is reachable within two taps. Dynamic EQ defaults to off (JVM test).

### U-1 Declutter and organise, keeping full control (D7) [proposed structure; the owner confirms it in §5]

**[verified-code] today.** Five tabs (`SvanApp.kt:121-125`: Sound, EQ, Presets, Hi-Fi, Lab). Hi-Fi (`AudiophileScreen.kt`) has
15 section headers and 21 controls. The Svaresa panel has 6 headers and 9 controls, EQ has 5 and 8, and Sound has 4 and 4.

**Proposed**
- Four tabs: Sound, EQ, Presets, Engine. A settings sheet behind the gear icon holds Diagnostics, Export and restore (moved
  from `PresetsScreen.kt:128-129`), Music detection setup, and About and licences.
- Sound: Auto master first, with its on/off switch and its switches. Then Tuners (bass, vocal, instruments, stereo). Then
  Output level (preamp, auto headroom, gain protection). Each group collapses to one summary line.
- EQ: the curve and bands, calibration, dynamic EQ (opt-in), and the Svaresa layer shown as a read-only curve.
- Engine (from Hi-Fi): mode (system effects or audiophile), quality, output bits and dither, apps and engines (identified
  apps only; blocked apps labelled), one Music detection status card, the signal path (read-only), and readings under Advanced.
- Remove: the Recording mode section (R-1), and the duplicate status cards, merged into one status strip.
- Rules: at most six section headers on first view of any screen. Progressive disclosure for everything else. One gold accent
  for the primary action. No new hues.

**Acceptance:** CI screenshots at phone width, in light and dark, reviewed by a person (AGENTS: "look at the screenshots").
Nothing is removed except R-1 and the W-1 name. Every former control is reachable within two taps.

### Docs and site
- `README.md`: remove the Recording mode sentence and its link. Update the feature list: no recording, and no whole-phone name.
- `docs/PRIVACY.md`: remove the blind-listening capture wording. State the ignore list in plain words. State that whole-phone
  output processing is off by default.
- `docs/site-assets/09-blind-listening.png`: replace it or drop it.

## 3. Verification

- **Core** (Claude lane, unchanged). From a full checkout:
  `cmake -S core -B build -DEQCORE_BUILD_TESTS=ON && cmake --build build -j4 && ./build/eqcore_tests`.
  Measured at `fbe0680`: 215 tests, 0 failed. Run it from a full checkout, because the lab tests read assets and Kotlin sources.
- **Android:** `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug :app:assembleRelease`, with `ANDROID_HOME` set.
  Needs SDK 36, build-tools 36.0.0, NDK 27.0.12077973 and CMake 3.22.1. The owner's Codex status records a first clean build of
  16 min 25 s. If Maven Central returns HTTP 429, wait and retry.
- **Manifest:** `python3 android/scripts/check_manifest_permissions.py <compiled manifest dump>`.
- **CI:** the emulator run, and its screenshots.
- **Grep gates** from R-1, W-1 and L-2.

## 4. Do not

- Write, save or export captured audio anywhere, including temporary files.
- Add a notification listener, SMS, accessibility, storage or account permissions.
- Work around any app that refuses capture, Spotify included.
- Turn whole-phone output processing on by default, or name it in the UI.
- Reintroduce permanent BLOCKED verdicts (`SessionRouter.kt:777`).
- Change the DSP or the core C++. File a request instead.
- Open a PR, cut a release or publish, unless the owner asks.

## 5. Owner decisions still open (answer before merge)

1. R-2: keep blind listening with WAV import only **[proposed]**, or remove it entirely?
2. W-1: offer no control at all **[proposed]**, or a neutral control under Advanced?
3. W-1: suspend the fallback during calls **[proposed]**?
4. U-1 and L-2: confirm four tabs (Sound, EQ, Presets, Engine), the settings sheet, and removal of the Lab tab.
5. L-1: on Engine A (system effects), keep the trim labelled "estimated" **[proposed]**, or turn it off there?

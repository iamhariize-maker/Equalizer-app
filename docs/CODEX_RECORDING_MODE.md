# Codex brief: audio-only Recording mode (final shape)

Read `AGENTS.md`, `docs/RECORDING_MODE.md` and `docs/HANDOFF.md` first. This brief supersedes the
"screen + sound" direction in `RECORDING_MODE.md`. The owner runs Svan on a TECNO LH7n (Android 14) and films that
phone's screen from outside with a second phone (LG V60, Android 13), then combines the LG video with Svan's
audio files in the VN video editor.

## Prompt (paste to Codex)

You are working on Svan (Android, Kotlin + C++), repo `iamhariize-maker/Equalizer-app`, branch
`ccr-c220a1e1-hikg7s` (do not push elsewhere, no PR unless asked). Read `AGENTS.md` first and obey its rules:
measured claims only, tests first for anything numeric, audio thread allocation-free and wait-free,
gold-on-charcoal design with no new hues, commit trailers as in `git log`, verify via CI screenshots/logs.

### Why
Android screen recorders cannot hear Svan's audiophile engine (its output opts out of playback capture so it
never re-captures itself; a second MediaProjection can also disturb the recorder). So the owner films the TECNO's
screen with the LG as a camera. The LG's microphone is NOT the audio source: the real audio is Svan's own
processed output saved as files, laid under the LG video in VN. The hard part is syncing the two easily. "Recording mode" (Hi-Fi tab) already exists: `listening/ProofRecorder.kt`, `ProofAnalysis.kt`,
`ProofCapture.kt`, `ScreenDemoRecorder.kt`, hooks in `CaptureService.kt` (`offerDry` before the DSP,
`commitWet` after), card in `ui/AudiophileScreen.kt`, JVM tests in `ProofRecorderTest.kt`
(all passing off-device). It records sample-aligned dry and processed 24-bit WAVs, measures each settled
setting change as its own segment, and writes charts + `svan-proof-report.json`.

### Goal
Make Recording mode **audio-only and edit-friendly**. Keep it honest: it is Svan's digital output, not a DAC,
Bluetooth or acoustic measurement.

### Tasks
1. **Audio-only is the default and only visible mode.** Remove the "Screen + sound" pill and screen path from
   the UI. Delete `ScreenDemoRecorder.kt`, `CaptureService.activeProjection/screenUsed`, the `WetTap` screen
   wiring and the Movies/MediaStore video code, unless you can show it works on the LG V60 (Android 13) and
   TECNO LH7n (Android 14) via the owner; if unproven, remove it. Less MediaProjection use also helps the
   Play policy item in `HANDOFF.md`.
2. **Make sync nearly effortless (the LG films the TECNO's screen, so the screen and room are the common
   reference).** Build all three, each independent so any one is enough in VN:
   a. **On-screen recording clock.** While recording, show a very large, high-contrast clock `m:ss.mmm` that is
      the exact recording time of the WAVs (derive from frames written / sample rate, not wall time), plus the
      current segment label in large text. Keep the screen awake (`FLAG_KEEP_SCREEN_ON`), fix brightness
      readable by a phone camera (gold/white on black, no new hues beyond AGENTS rules), and avoid a layout that
      reflows. Because every video frame then shows the clock, the owner can read the clock on any frame and
      offset the WAV in VN.
   b. **Sync button with a flash and a click.** Add a "Sync" button (also reachable from the countdown below).
      Pressing it calls a new `ProofRecorder.markSync()` that stores the exact frame index (`written`/2, same
      audio-thread-safe position semantics as `mark`) and `System.currentTimeMillis()`; shows a full-screen white
      flash for ~150 ms; and plays a short, sharp **three-click cue** (three 2 kHz 10 ms bursts 250 ms apart,
      peak about -6 dBFS) through the **phone speaker only** (`AudioTrack` with
      `ALLOW_CAPTURE_BY_NONE`, `setPreferredDevice` to the built-in speaker if present, volume control not
      touched). The cue must never enter `offerDry`/`commitWet`, the WAVs or the headphone path. The LG's
      microphone hears the clicks, so in VN the owner can line the click waveform in the LG video's audio up with
      the click burst in the sync WAV below (visual waveform match), then mute the LG audio.
   c. **3-2-1 start.** An optional "Start with countdown" button: big 3, 2, 1 on screen, then recording
      begins on 0 with an automatic sync flash+click. This gives a single clean aligned moment at time zero.
   Allow several syncs; the report lists all, in order, with seconds. After each press show "Sync at m:ss.mmm".
3. **Sync-aligned exports.** At stop, besides the full WAVs, write (only if a sync was pressed or the countdown
   was used) `svan-processed-from-sync.wav` and `svan-dry-from-sync.wav`, each starting exactly at the *first*
   sync frame (copy from the finished full WAVs, rewrite the header), and `svan-processed-sync-cue.wav`, a copy of
   the processed-from-sync file with the same three-click cue mixed in at its start (same peak, so VN waveform
   matching is trivial; this copy is for aligning only, not for the final video). Put `syncFrames`, `syncSeconds`,
   `firstSignalSeconds` (first block above -90 dBFS in either stream), `clockStartEpochMs` and
   `recordedAtEpochMs` into the report. Measure and report (do not guess) the speaker-click's output latency
   only if you can do it with a documented method; otherwise state that the cue is accurate to a few tens of
   milliseconds and that the on-screen clock is the precise reference.
4. **Editor compatibility.** Also export the processed stream as `svan-processed-output.m4a`
   (AAC-LC, 48 kHz stereo, 256 kbps) using MediaCodec + MediaMuxer, in the writer/finishing path, never on the
   audio thread. Ask the owner (or note in the docs) whether VN imports 24-bit WAV on the LG V60; if not, make
   16-bit PCM WAV (TPDF dither) the default WAV and say so. Do not change DSP output.
5. **Keep the per-setting segments** (`mark`, `SettingsDiff`, `svan-settings-effects.png`). Fix any
   defects you find. Add a "Mark now" button for a manual segment boundary with a typed label (max 40 chars),
   so the owner can name what they are about to demonstrate.
6. **Level matching.** Add to the report and chart, per segment, the processed-vs-dry loudness difference
   (RMS is fine; do not claim LUFS unless you implement and test BS.1770), and a one-line note that louder
   sounds better, so comparisons across segments should be judged at matched level. The existing Blind Lab
   `BlindRenderer` level-matches; reuse its approach for an optional `svan-processed-matched.wav` whose gain is
   chosen so each segment's RMS equals its dry RMS (report the applied gain per segment). Default off.
7. **UI polish for demos.** While recording, show a large elapsed timer, the current segment label, a clear
   Stop button, and the sync/mark buttons. Keep all strings plain; follow the design rules.
8. **Docs.** Rewrite `docs/RECORDING_MODE.md` to the audio-only flow with a step-by-step "make a video in VN"
   section: start the engine → set up the LG to film the TECNO screen → start Svan recording with countdown (or
   press Sync) → demonstrate settings → stop → in VN import the LG clip and `svan-processed-from-sync.wav` (and
   `svan-processed-sync-cue.wav` for alignment) → match the click waveform / flash frame / on-screen clock →
   delete the cue copy and mute the LG audio. Update `HANDOFF.md` and the AGENTS.md "Top open items".

9. **Before/after in one take (A/B timeline).** The dry and processed WAVs are sample-aligned captures of the same
   audio, so a "before" never needs the engine off. Add two buttons to the recording card, "Before" and "After".
   Each press calls `ProofRecorder.markAb(isAfter: Boolean)`: it stores the exact frame index and the choice (same
   audio-thread-safe position semantics as `mark`/`markSync`), shows the choice in large text on screen with the
   clock, and also starts a new measured segment labelled "Before"/"After" (reuse `mark`). It does not change what
   the owner hears live and does not change DSP; the owner may also flip a real setting on camera for the visual.
   At stop, when at least one A/B press exists, write `svan-ab-timeline.wav`: from the first A/B press to the end,
   the output follows the choice (dry for Before, processed for After) with a 5 ms equal-power crossfade at each
   switch (no clicks), starting in Before unless the first press was After. Optional per-segment RMS level
   matching (task 6) applies so After is not simply louder; default ON for this file and reported (applied gain
   per segment). Also write `svan-ab-timeline-from-sync.wav` (starting at the first sync frame) when a sync exists.
   Report `abSwitches: [{frame, seconds, choice}]`. Document: film the TECNO screen, press Before/After on the
   beats you want, then in VN use `svan-ab-timeline-from-sync.wav` as the only audio track (no manual cutting).
   Tests: crossfade is click-free (max sample step across a switch below a stated bound for a 1 kHz tone at
   -6 dBFS), switch positions are exact, timeline equals dry/processed outside the 5 ms fades, and level matching
   makes After's RMS equal Before's within 0.05 dB on a +6 dB test signal.

### Tests (must be in `testDebugUnitTest`, and add to CI if needed)
- Extend `ProofRecorderTest`: `markSync` frame position is exact; `from-sync` WAVs equal the tail of the full
  WAVs sample-for-sample and have a correct header; `firstSignalSeconds` for a tone that starts at 1.5 s;
  16-bit/24-bit conversion round-trips; matched-gain output equals dry RMS within 0.05 dB.
- Keep the existing 6 tests green. Keep `./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest`
  clean and the emulator e2e `PASS` set intact.
- Add an emulator check if feasible: with the testsource tone app on Engine B, start recording, press Sync,
  stop, and assert the files exist in MediaStore with non-zero length.

### Owner phone checks (write them into `docs/PHONE_VALIDATION.md`; do not claim they passed)
TECNO LH7n (Android 14) runs Svan; LG V60 (Android 13) films the TECNO screen:
1. Is the on-screen clock readable in the LG video at normal distance and room light? Is the flash visible?
2. Does the LG microphone pick up the speaker click cue clearly? How far apart (ms) are the video flash and the
   click in VN? Does the clock reading, the flash and the click agree to within one video frame?
3. Do the saved WAV/M4A play and import into VN? Which format does VN accept?
4. With the owner's headphones on the TECNO, does the edited A/B sound like what they heard live?
5. Does the click cue stay out of the saved WAVs and out of the headphones?

### Do not
- Do not add storage, accessibility, notification-listener or SMS permissions (MediaStore needs none).
- Do not record other apps' audio beyond what Engine B already captures; do not upload anything.
- Do not claim sound-quality, loudness (LUFS) or latency numbers you have not measured in a test.
- Do not touch the DSP chain or the audio-thread contract of `offerDry`/`commitWet`.

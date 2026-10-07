# Codex brief: audio-only Recording mode (final shape)

Read `AGENTS.md`, `docs/RECORDING_MODE.md` and `docs/HANDOFF.md` first. This brief supersedes the
"screen + sound" direction in `RECORDING_MODE.md`. The owner will record the video with the LG V60's own
screen recorder (sound off) and combine it with Svan's audio in the VN video editor.

## Prompt (paste to Codex)

You are working on Svan (Android, Kotlin + C++), repo `iamhariize-maker/Equalizer-app`, branch
`ccr-c220a1e1-hikg7s` (do not push elsewhere, no PR unless asked). Read `AGENTS.md` first and obey its rules:
measured claims only, tests first for anything numeric, audio thread allocation-free and wait-free,
gold-on-charcoal design with no new hues, commit trailers as in `git log`, verify via CI screenshots/logs.

### Why
Android screen recorders cannot hear Svan's audiophile engine (its output opts out of playback capture so it
never re-captures itself; a second MediaProjection can also disturb the recorder). The owner needs the real
processed output as files, to lay under a separately recorded screen video that shows Svan's settings being
changed. "Recording mode" (Hi-Fi tab) already exists: `listening/ProofRecorder.kt`, `ProofAnalysis.kt`,
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
2. **Sync marker for the video editor.** Add a "Sync" button to the recording card (and to the Svaramanas
   bubble/QS tile if cheap). Pressing it: (a) calls a new `ProofRecorder.markSync()` that stores the exact frame
   index (`written` floats / 2, audio-thread-safe, same position semantics as `mark`) plus `System.currentTimeMillis()`;
   (b) flashes the whole screen white for ~150 ms (a Compose overlay or window flash visible to the phone's
   screen recorder) so the owner can find one video frame to align to. Allow several syncs; the report lists
   all, in order. On the card show "Sync at m:ss.mmm" after each press.
3. **Sync-aligned exports.** At stop, besides the full WAVs, write `svan-processed-from-sync.wav` and
   `svan-dry-from-sync.wav`, each starting exactly at the *first* sync frame (copy from the finished full
   WAVs, rewrite the 24-bit header). Without a sync press, skip them. Put `syncFrames`, `syncSeconds`,
   `firstSignalSeconds` (first block above -90 dBFS in either stream) and `recordedAtEpochMs` into the report.
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
   section: start engine → start Svan recording → start the phone's screen recorder with sound OFF → press Sync
   (note the flash frame) → demonstrate settings → stop both → in VN place `svan-processed-from-sync.wav` on
   the flash frame. Update `HANDOFF.md` and the AGENTS.md "Top open items".

### Tests (must be in `testDebugUnitTest`, and add to CI if needed)
- Extend `ProofRecorderTest`: `markSync` frame position is exact; `from-sync` WAVs equal the tail of the full
  WAVs sample-for-sample and have a correct header; `firstSignalSeconds` for a tone that starts at 1.5 s;
  16-bit/24-bit conversion round-trips; matched-gain output equals dry RMS within 0.05 dB.
- Keep the existing 6 tests green. Keep `./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest`
  clean and the emulator e2e `PASS` set intact.
- Add an emulator check if feasible: with the testsource tone app on Engine B, start recording, press Sync,
  stop, and assert the files exist in MediaStore with non-zero length.

### Owner phone checks (write them into `docs/PHONE_VALIDATION.md`; do not claim they passed)
LG V60, Android 13, built-in screen recorder with sound OFF:
1. Does starting the audiophile engine stop the phone's screen recorder? Try both orders (recorder first, or
   engine first) and report which works.
2. Do the saved WAV/M4A play and import into VN? Is the sync flash visible in the video?
3. With the owner's headphones, does the video-edited A/B sound like what they heard live?

### Do not
- Do not add storage, accessibility, notification-listener or SMS permissions (MediaStore needs none).
- Do not record other apps' audio beyond what Engine B already captures; do not upload anything.
- Do not claim sound-quality, loudness (LUFS) or latency numbers you have not measured in a test.
- Do not touch the DSP chain or the audio-thread contract of `offerDry`/`commitWet`.

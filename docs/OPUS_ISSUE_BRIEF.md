# Opus brief: Svan issues found on 8 October 2026

Status: investigation only. This pass changed no behaviour. The one code change on this branch is a logging probe,
commit `47849e3` (CI run #213 verifies it). Read this file, then `AGENTS.md`, then the sections in priority order.

Labels used below:
- **[verified-code]**: read in the code path.
- **[verified-log]**: seen in CI logs (run #210, API 34 emulator).
- **[verified-screenshot]**: seen in CI screenshots.
- **[hypothesis]**: needs the probe or a device to confirm.
- **[design]**: a product decision, not a code bug.

## 0. Ground rules for the fix pass
- Work on the branch the session names (`ccr-9eafc6c2-mru90o` here). Fast-forward pushes only. No force-push.
- Every capture-loop change ships with an emulator scenario in CI. The emulator runs only in GitHub Actions (KVM).
  Local checks: `eqcore_tests`, ASan build, `tools/mastering/test_mastering.py`.
- Do not "fix" anything in section 6. It was checked and is correct.
- The Kotlin and Android lane belongs to Codex (`docs/audio-quality/COORDINATION.md`). Log Kotlin changes in
  `docs/audio-quality/REQUESTS_FROM_CLAUDE.md`, as before.

## 1. How the crackle evidence was read (so it can be checked again)
- Source: CI run #210 (`ac39f47`), `e2e-results-api34` artifact, `e2e_eqspike_full.log`. The engine logs a
  `capture level` line every 2 s: peak, output queued ms, underruns, DSP %, muted packages, other players. The
  interval is measured, not assumed (median 2005 ms).
- The emulator ran at 48 kHz in the default SAFE mode. No hi-res session ran in CI (see T1).
- AudioTrack underrun counters reset when the track is rebuilt. The log has four `capture: started` lines, so
  counts below are per track, not cumulative across the run.
- Probe: CI run #213 (`47849e3`) adds `capture timing:` lines with `readWaitMaxMs`, `dspMaxMs`, `writeWaitMaxMs`,
  `overBudgetBlocks` and `blockMs` (5.33 ms at every rate). Read those first.

## 2. Findings in priority order

### P0: the crackle path (the owner's main complaint)

**A1. Output starves when the source goes quiet.** [verified-log] [hypothesis on cause]
- Log lines 11 to 13 of the capture sequence: a tone at peak 0.25 with 70.7 ms queued and 0 underruns. The next
  window shows 29.3 ms queued, underruns 0 to 5, DSP 2.1 %. Then the source is gone (peak 0, `muted=[]`): queued
  5 to 45 ms, underruns 5 to 7 over about 50 s, DSP 0 %.
- Not every quiet window starves, and the trigger is not simply silence. The probe will show which state applies.
- Two candidate causes:
  - (a) When no source is admitted, the recorder reopens with only Svan's own UID (`CaptureService.kt:433`). Svan
    opts out of capture (`AndroidManifest.xml:80`), so the read may return nothing and block.
  - (b) Capture only delivers frames while the source plays, so `READ_BLOCKING` (`CaptureService.kt:265`) waits.
  - Test: during the silent windows, `readWaitMaxMs` far above `blockMs` means the thread is waiting for capture,
    which points to (a) or (b). A small read wait with a draining queue would point at the write side instead.
- Fix, preferred (Option A): decouple. The capture side writes into a ring buffer. A playback thread pulls from the
  ring at real-time pace and writes faded silence whenever the ring is empty. The track never runs dry.
- Fix, smaller (Option B), if A is too big for one pass: when no source is admitted, do not open capture. Pace the
  silence with a monotonic clock, one block per 5.33 ms. Reopen capture with a fade-in when a source returns.
- Acceptance, as a new CI scenario: play a tone, stop the source for 3 s, resume it. Underruns must not rise during
  the stop. Queued audio must reach at least 60 ms within 2 s of resuming. Add a discontinuity check to the e2e
  helper so a click shows up as a failure.

**A2. The cushion never refills after a stall.** [verified-log] [verified-code]
- After the silence, queued audio sat at 16 to 33 ms for about 12 s. Then underruns went from 0 to 12 while DSP was
  at 0.8 % (sequence lines 95 to 103).
- The write loop only writes what capture delivers (`CaptureService.kt`, about lines 310 to 317). Startup primes the
  80 ms cushion once (line 227). The recovery path grows the buffer size (line 373) but writes nothing into it. The
  buffer gets bigger, and the audio does not.
- Fix: Option A refills automatically. As a stopgap, after any underrun write faded silence until queued audio
  reaches the cushion.
- Acceptance: the A1 scenario, plus a forced single stall. Queued audio should return to at least 60 ms within 1 s.

**A3. Hi-res modes probably leave the low-latency path.** [hypothesis]
- `EVIDENCE_HIGH_RATE` and `EXPERIMENTAL_192K` (`CaptureFormat.kt:61` to `63`) open the output track at 88.2, 96,
  176.4 or 192 kHz while the mixer runs at 48 kHz. The `capture: rates:` line already logs `mixer-hint`.
- Android's low-latency fast path generally needs the track rate to match the mixer rate. Otherwise the track goes to
  the normal mixer, with larger bursts and a resampler. That would explain underruns that appear only at high rates,
  and why an 80 ms cushion is not enough. This is unconfirmed.
- Test on a phone (owner's TECNO, or the LG V60) in `EVIDENCE_HIGH_RATE` with a 96 kHz route. Capture
  `dumpsys media.audio_flinger` during playback and compare the track's flags and burst size with 48 kHz. Compare
  underrun rates over the same listening period. Put the track flags into the diagnostic report.
- Owner decision 1 depends on this result (section 3).

**A4. A single write error ends the whole capture session.** [verified-code]
- `CaptureService.kt:315`: `if (count <= 0) { running = false; break }`. The epoch returns false, the audio loop exits,
  and `finally` calls `stopSelf()`. A transient `ERROR_DEAD_OBJECT` or invalid-state result therefore ends capture
  with no message to the user.
- The read-error branch just after the read has a restart path. The write path has none.
- Fix: treat a write error like a read error. Restart the epoch once in Fast at 48 kHz, then show a recovery message.
  Keep the silent stop only for a user stop.
- Acceptance: needs the AudioTrack seam in T3. Until then, record this as untested.

**A5. Priority inversion on `engineLock`.** [verified-code] [hypothesis on impact]
- The audio thread takes `engineLock` on underrun recovery (spatial limit change, `CaptureService.kt:350`) and on
  source reconfiguration (`:327`). The watcher thread (normal priority, `:203`) holds the same lock while it calls JNI
  setters. If the watcher holds the lock when the audio thread needs it, the URGENT_AUDIO thread waits. Recovery is
  triggered by underruns, so this can add a stall during the very event it is handling.
- Fix: the audio thread must not take the lock. Publish spatial-limit and mode changes as atomics, and let the audio
  thread apply them between blocks.
- Acceptance: a stress test that toggles the spatial limit while the watcher runs. `readWaitMaxMs` and `dspMaxMs`
  should stay flat in the probe logs.

**A6. An unrouted sibling session can stop all capture.** [verified-code]
- `CapturePolicy.kt:18` to `25`: a UID is eligible only when every route for it is Engine-B-muted and no other active
  media session from that UID is observed outside those routes. A second media session from the same app that has
  started but is not yet routed therefore makes the whole UID ineligible.
- `SessionRouter.kt:62`: when a muted route's UID is no longer eligible, the router logs "conflicting UID routes;
  stopping safely" and stops the whole service. There is no debounce and no per-source fallback. The user sees no message.
- `CapturePolicyTest.kt:50` covers the eligibility rule. Nothing tests the consequence, which is the service stop.
- Fix: route the new sibling session into Engine B on discovery, or fall back that UID to Engine A until it is routed.
  Debounce the decision (for example, one second stable) before acting. Stop everything only for a conflict that persists.
- Acceptance: a router-level test with a sibling session that starts, waits 300 ms, then gets routed, and a second
  sibling that never routes. The service should keep running in the first case.

**A7. Per-block allocation on the audio thread.** [verified-code] low priority
- `CaptureService.kt:263`: `val sourceChanged = next != allowed` compares two Sets every block. Equal non-empty sets
  allocate an iterator, roughly 187 times a second while a source is admitted. AGENTS.md rule 3 forbids this.
- Fix: an identity check is enough. `SessionRouter.kt:60` assigns `captureUids` only when the contents change.
  Document that invariant next to the field.

### P1: coverage and quality decisions

**T1. Hi-res capture is never tested in CI.** [verified-code] high priority
- Only the default SAFE 48 kHz path runs. The `192000` matches in `android/scripts` are WAV header bytes in the host
  audio helper, not capture rates. Nothing sets `captureRateMode` in the scripts.
- Fix: add a CI scenario that requests `EVIDENCE_HIGH_RATE`. If the emulator does not report a 96 kHz route, the
  scenario must say "not available on this device" and must not count as a pass. Check the logged
  `routedDevice.sampleRates` first.

**T2. No scenario covers stop, pause or source switch.** [verified-code] high priority
- That is where A1 shows up. Add the scenario from A1.

**T3. The capture loop has no unit tests.** [verified-code] medium priority
- `CaptureService` is covered only end to end. Put an interface over `AudioRecord` and `AudioTrack` so the loop can
  run against fakes. Cover write errors (A4), read gaps (A1), rate changes and route flips (A6).

**Q1. Body saturation may conflict with "no grit".** [design]
- `docs/SONIC_IDENTITY.md` (grounding section) says the third harmonic is about -32 dBc at -12 dBFS at full depth.
  The baseline of 0.7 lowers that by about 3 dB. Odd harmonics are usually heard as harsher than even ones, and the
  owner asked for no gritty distortion.
- Decide the baseline by blind test, using the owner's protocol. One option is a lower default, or off until tested.

**Q2. House voicing and mid-bass.** [design]
- The 65 Hz foundation (up to +3 dB on thin mixes) and the 180 Hz body (+0.75 dB) add low-mid weight. The owner said
  "not mid-bass boom" on bass-heavy earbuds. Check the Metro Boomin track first in the listening test.

### P2: UX and copy (seen in the CI screenshots, run #210)

**U1. Developer copy on user-facing screens.** [verified-code] [verified-screenshot]
- `ui/OnboardingUi.kt:174`: "Pairing cannot be verified separately from a running Shizuku binder. Its running tick is
  the observed result of startup, not a claim about a stored pairing." Rewrite it in plain words.
- Hi-Fi card (`screens/4-hifi.png`): "Routing is observed here; measured response and phone compatibility need separate
  checks." Shorten it.

**U2. Monospace text for explanations.** [verified-code] [verified-screenshot]
- `ui/Theme.kt:98` sets `labelSmall` to `FontFamily.Monospace`. Explanations and status lists therefore read like a
  console (for example `detection/wizard-install.png`). Keep monospace for numbers and diagnostics only.

**U3. Crowded and truncated EQ labels.** [verified-screenshot]
- The EQ header shows "Svaresa · A…" (`screens/1-eq.png`). Band handles overlap between about 50 and 250 Hz, and again
  near 2 to 10 kHz, so they are hard to tell apart.
- The "Open" affordance on the Sound screen is very small (`screens/0-sound.png`).

**U4. Svaramanas sheet clipping.** [verified-screenshot] confirm first
- At 320 by 640 px, the last chip ("Intimate") is cut off at the bottom (`screens/6-svaramanas.png`). Check that the
  sheet scrolls. If it doesn't, fix it.

**U5. The planner runs on the main thread.** [verified-code] impact not measured
- `svaramanas/Svaramanas.kt:174` uses `Dispatchers.Main`. `SmartPlan.compute` (the native plan) runs from `update()`
  on every slider change, and every `UPDATE_MS = 3000L` (`:247`).
- Fix: move compute to `Dispatchers.Default` and publish the result through a StateFlow. Measure frame times during a
  slider drag before and after.

### P2: robustness and latent issues

**L1. JNI processing has no handle guard.** [verified-code] latent
- `android/app/src/main/cpp/jni_bridge.cpp:111`: `fromHandle(h)->process(...)` with no check for `h == 0`.
  `NativeEngine.close()` sets the handle to 0 (`NativeEngine.kt:113` to `117`). Calling process after close would
  dereference null. The current code paths don't do that. Add the guard anyway.

**L2. A shadowing warning in core.** [verified-build] trivial
- `core/src/svaramanas.cpp:221` shadows `ref` (`-Wshadow`). The rest of core builds clean with
  `-Wall -Wextra -Wshadow`.

**L3. The preview keystore is tracked in Git.** [verified-code] owner decision
- `android/preview.keystore` is in the repository. It looks like the preview or test key. Confirm it is never used
  for distribution. Alternatively, generate it per run, like the disposable CI key.

**X1. The output cushion can grow to 240 ms.** [design]
- The owner's TECNO log showed 262.6 ms queued over Bluetooth. Growth adds latency and does not refill (A2). The
  owner needs to set the latency ceiling (section 3).

### P3: docs and process

**D1. Stale branch names and links.** [verified-code]
- `AGENTS.md` names `ccr-c220a1e1-hikg7s`. `docs/index.html:117` links to `claude/codex-audio-crackling-amplifier-gkj007`.
  Several CODEX docs name old branches too. Future agents may push to the wrong place. Point them at the session's
  branch.

**D2. An old beta workflow.** [verified-code]
- `.github/workflows/stage-beta-0.5.6.yml` runs only on an old branch name. Confirm it is dead, then remove or update it.

**D3. CI runs on every push, with no filters.** [verified-code]
- `ci.yml` has `on: push:` with no branch filter, no `paths` filter and no `concurrency` group. Docs-only commits run
  the full emulator job, about 35 minutes. A `concurrency` group would let a newer push cancel an older run. A paths
  filter would skip docs-only changes.

## 3. Owner decisions needed (do not decide these in code)
1. Hi-res exposure: keep `EXPERIMENTAL_192K` visible with a warning, or hide it until A3 is measured?
2. Body saturation baseline (Q1): default lower or off, or wait for a blind test?
3. Latency ceiling (X1): the largest cushion over Bluetooth the owner accepts.
4. Preview keystore in Git (L3).
5. Wording of the Shizuku and wireless-debugging disclosure (see section 8).

## 4. Suggested order for the fix pass
1. Read the probe from CI run #213 (`47849e3`). It decides between A1(a), A1(b), the write side, or CPU.
2. A1 and A2 together (ring buffer or silence pacing), with the T2 scenario in CI.
3. A4 (restart on write error) and the T3 seam.
4. A5 (the lock), then A6 (route fallback) with its unit test.
5. A3 on a phone, then decide the hi-res UI (owner decision 1).
6. U1 to U5, then L1 to L3, then D1 to D3.

## 5. Verification checklist for any fix
- `cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests` (178 at the time of writing).
- ASan and UBSan core builds (CI).
- `python3 -W ignore tools/mastering/test_mastering.py` (12 tests).
- CI emulator e2e: keep every existing PASS set intact (AGENTS.md lists them), and add the new scenario.
- Read the new `capture timing` lines in the artifact, and put the numbers in the commit body.
- Review `screens/*.png` after any layout change.

## 6. Verified fine: do not change
- Engine block chunking (`core/src/engine.cpp:140`). Frames above `maxBlock` are split, so hi-res block sizes fit.
- Teardown order in `audioEpoch`'s `finally` (`CaptureService.kt`, about lines 400 to 410). The watcher is joined,
  `current` is cleared under the lock before `close()`, and `NativeEngine.close()` is idempotent.
- JNI critical arrays for `in == out` (`jni_bridge.cpp:106` to `114`) are correct.
- `ClipRecorder.offer` and `ProofRecorder.offerDry` and `commitWet` are allocation-free while idle
  (`listening/ClipRecorder.kt:19` to `25`, `listening/ProofRecorder.kt:103` to `121`).
- The denormal guard in `parametric_eq.cpp:11` (1e-25). Keep it. Extend it to other IIR stages only if the probe
  shows CPU spikes.
- The 1 s silence skip in the capture loop is intentional.
- No TODO, FIXME or HACK markers in the code.

## 7. Limits of this investigation
- No phone, no Android SDK and no KVM in this container. Nothing here ran on a device, and nothing was listened to.
- The emulator is an x86 host with virtual audio. Its timing does not represent phone efficiency cores.
- The CPU figures in `docs/AUDIOPHILE.md` are host numbers. The doc itself says phones are several times slower.

## 8. Security, privacy, manifest and licensing sweep

A read-only sweep ran on `a979f0b` (no code changed). Each row is the sweep's finding. The notes after the table are my
own checks: "confirmed" means I re-read the line, and "severity note" is my adjustment.

| ID | Sev (sweep) | Location | Risk | Confidence |
|---|---|---|---|---|
| S1 | high | `android/app/src/main/java/app/svan/MainActivity.kt:85`, `:179` to `:181`, `:212`, `:250` to `:257`; `core/src/biquad.cpp:31` | In the preview APK, any installed app can send `--es cmd` to the exported launcher and change EQ or stop audio. Gain is reported as unclamped. | by reading |
| S2 | high | `android/preview.keystore`; `android/app/build.gradle.kts:47` to `49`, `:59`; `.github/workflows/ci.yml:4`, `:67` to `70` | Preview and CI builds are signed with a public key whose password is in the Gradle file. A repackaged APK could install over a preview install. | by reading, the impact is inferred |
| S3 | medium | `AndroidManifest.xml:96` to `108`; `SessionContinuity.kt:35` to `36`; `SessionRouter.kt:219` to `233` | In both builds, an exported receiver and activity without a permission accept any claimed package and session, so one app can take over another app's session. | by reading |
| S4 | low | `EqController.kt:19`; `SessionReceiver.kt:28` | Every build logs every line to logcat, including music-app package names and start and stop times. No track titles found. | by reading |
| S5 | low | `DiagnosticReport.kt:12` to `16`, `:71` to `77`, `:92` | The shared diagnostic report lists package names and output-device addresses, probably Bluetooth MAC addresses, despite its "no account data" docstring. | by reading |
| S6 | low | `listening/ProofCapture.kt:137` to `171` | Hi-Fi exports leave captured dry playback and the device model in shared Music and Download folders until deleted. | by reading |
| S7 | low | `docs/OWNER_REFERENCE_TASTE.md:57` and `:72`; `Svaramanas.kt:226` | The doc asks for audio uploads at line 57 and says audio is never uploaded at line 72. The code keeps taste on the device. | by reading |
| S8 | low | `tuning/AutoEqSource.kt:17`, `:96`, `:101` | The only network call reveals the chosen headphone and the device IP to GitHub. Downloads have no checksum and no size cap. | by reading |
| S9 | low | `.github/workflows/ci.yml:1` to `6`, `:10`, `:157`; `stage-beta.yml:10`, `:22` | No token `permissions:` block, and action tags are mutable, unlike the release workflows. | by reading |
| S10 | low | `core/tests/data/README.md:1` to `2`; `docs/site-assets/fonts/DejaVu-notice.txt:58`; `docs/SPIKE.md:55` to `56` | Thin notices. AutoEq has only an MIT summary. A GPL-2+ DejaVu notice ships with no DejaVu font. There is no in-app Apache-2.0 notice. "No GPL copied" rests on reading RootlessJamesDSP. | by reading |

**Clean areas (from the sweep, not all re-checked):** not debuggable in the built APK; `allowBackup=false` and no
cleartext config; minSdk 29, targetSdk 36; the mediaProjection foreground service type; no notification listener
(`check_manifest_permissions.py`); one HTTP client and no analytics, crash SDK, WebView or socket code; production
builds reject command extras (`MainActivity.kt:85`); ClipRecorder is memory-only; settings export is user-chosen;
no `secrets.` or `pull_request_target` in any workflow; DUMP is granted only after a user tap.

**My severity notes, after checking:**
- **S1 confirmed.** `handleCommand` returns early only when `BuildConfig.PHONE_PREVIEW` is false. Production is safe.
  For the preview APK, fix it by removing command handling from the exported launcher, or by restricting it to
  debug-only with a signature-level permission. Clamp the gain in the same change. Keep the test scripts working.
- **S2 confirmed, rated medium rather than high.** Preview builds are designed to share one public key so testers
  can update in place (`build.gradle.kts:43` to `44`). The owner's production install is signed with a different key,
  so this key cannot update it. The risk is limited to preview testers. Owner decision 4 (section 3) covers it.
- **S3 confirmed.** The receiver and activity are exported with no permission (`AndroidManifest.xml:96` to `108`).
  Fix by checking the sender (`getSentFromUid()` on API 34 and later, or a signature permission), and by validating
  the claimed package against the session it is reporting.
- **S7** is a documentation contradiction. Fix the wording in `OWNER_REFERENCE_TASTE.md`.
- **S9 and S10** are low-effort hardening: add a `permissions:` block, pin action SHAs, and ship only the notices that
  match what is bundled.
- **D2 check:** the sweep names `.github/workflows/stage-beta.yml`, and the brief named `stage-beta-0.5.6.yml`. The
  file list confirms what is present. Check both before removing either.

**Added to the fix pass** (in order of risk): S1, S3, then S2 with the owner's decision, S5, S6, S8, S7, S9, S10, S4.

## 9. Status after the fix pass (8 October 2026)

Probe result (CI #213, API 34 emulator, `47849e3`, all 11 jobs green): during playback the longest DSP block was about 1.2 ms
against a 5.33 ms budget, with no block over budget; capture arrives in bursts of about 20 ms. When the tone stopped,
underruns went 0 to 17 while the longest read wait reached 46 ms and DSP was idle; afterwards writes never blocked (the
output was never full again) and the queue sat at 7 to 38 ms with occasional further underruns. CPU is ruled out on the
emulator: the thread waits for capture and nothing refills the cushion, which A1(a) and A2 below address.

Done in this pass (verified locally: core tests, Kotlin compile, JVM unit tests; emulator checks run in CI):
- **A1(a)** With no admitted source, the loop feeds paced silence instead of waiting on a recorder that can only match Svan's
  own opted-out output. **A2** During digital silence, the loop tops the output back up to the 80 ms cushion
  (`cushionTopUps` in the `capture timing` line). A1(b), a blocking read while an admitted source is paused, waits for the
  probe numbers.
- **A4** A write error restarts once in Fast at 48 kHz with a message, instead of ending the session.
- **A5** The audio thread no longer takes `engineLock` on underrun recovery (native setters are atomics; the watcher records
  the epoch). Reconfiguration builds the new engine outside the lock; the lock covers only the swap.
- **A6** A conflicting route now falls back to Engine A for that source only; capture continues for everyone else.
- **A7** The per-block source check compares identity first, so steady state never runs `Set.equals`.
- **L1** `nativeProcess` ignores a closed (zero) handle. Core now sanitises every band (non-finite values replaced, gain
  clamped to ±48 dB, `kMaxBandGainDb`), with the test `hostile_band_parameters_are_sanitised`.
- **S1** Automation commands are accepted only through the `.Command` activity alias, which requires DUMP (the adb shell
  has it; ordinary apps cannot get it). The launcher ignores `cmd` extras; an invalid `quality` no longer crashes. Test
  scripts use `.Command`; `production_release.sh` still checks that the launcher rejects commands.
- **S3** A session claim is ignored when the audio service attributes that session to a different app uid
  (`SessionAnnouncement.consistent`, unit-tested). Media-server owners are still accepted.
- **S5** Diagnostic reports mask hardware addresses to their last byte (unit-tested). **S7** The taste doc no longer
  contradicts itself. **S9** CI has a read-only token and a per-branch concurrency group.
- **U1** Plain copy for the Shizuku status and the Hi-Fi working card. **U2** `labelSmall` uses tabular figures instead of
  monospace. **U3** The EQ header stacks the preset name and the engine status. **U5** Svaramanas plans on one background
  thread; EQ state writes and the curve engine stay on Main.
- **Design (owner decision):** jewel accents with fixed meanings and drawn illustrations (`ui/Illustrations.kt`); see
  AGENTS.md rule 4. **Bubble:** drag it onto the close target at the bottom of the screen to switch it off.
- **D1** AGENTS.md no longer names a stale working branch.

Still open: A1(b) and A3 (need probe numbers and a phone), T1 to T3 (new CI scenarios and a capture seam), Q1 and Q2
(listening decisions), S2/L3 (owner decision on the preview key), S6, S8, S10, X1, D2.

# Frontier-model brief: Svan 0.5.13 issues from the 10 and 11 October 2026 reports

Status: investigation and preparation only. This pass changed no product code. The repo branch that carries this
brief (`claude/svan-messaging-filter-platforms-mxr9aw`) is based on 0.5.5 and is **not** the implementation base.
Every file and line below was read on the owner's build branch **`ccr-f8964344-8f7mf5`, head `fbe0680`, version 0.5.13**.
Read this file first, then that branch's `AGENTS.md`, then `docs/audio-quality/COORDINATION.md` (Kotlin lane rules),
`docs/DETECTION_FALLBACKS.md`, and `docs/OPUS_ISSUE_BRIEF.md` (the house format this brief copies).

Labels:
- **[verified-code]**: read in the 0.5.13 code path (`git show` at `fbe0680`).
- **[verified-probe]**: measured in this container on the 0.5.13 core, or host-only (see §4).
- **[verified-report]**: stated in the owner's pasted diagnostic (LG LM-V600, Android 13, Svan 0.5.13-audiophile-preview).
- **[hypothesis]**: plausible, not yet confirmed; an experiment in §3 decides it.
- **[design]**: a product or owner decision, not a bug.

## 0. Ground rules for the fix pass

- **Target branch:** `ccr-f8964344-8f7mf5` (or the branch the owner names). Fast-forward pushes only; no force-push.
  Do not implement on `claude/svan-messaging-filter-platforms-mxr9aw`, which is 0.5.5 and has none of `diag/`,
  `MixFallback` or `MusicSourcePolicy`.
- **Lanes:** the Kotlin and Android lane belongs to Codex (`COORDINATION.md`). Log each Kotlin change in
  `docs/audio-quality/REQUESTS_FROM_CLAUDE.md`. A paste-ready entry is in §6.
- **Owner decisions on 0.5.13 bind this work** (its `AGENTS.md`): no notification listener, SMS or accessibility
  capability for any purpose; Play Protect stays on; the Shizuku/DumpGrant route is granted only after the user's tap;
  "keep the independent session-broadcast path, existing/manual grants, Play Protect, and all DSP/routing quality
  safeguards". Run `android/scripts/check_manifest_permissions.py` on the compiled manifest before any push.
- **Do not add messaging, ride or payment apps to `<queries>`.** The ignore list stops processing; it must not add
  package discovery.
- **Numbers in UI and docs come from a test or a measurement** (AGENTS rule 2). Synthetic tests are not listening
  evidence, and no device compatibility is established by them.
- **Build facts:** Android needs platform 36, build-tools 36.0.0, NDK 27.0.12077973 and CMake 3.22.1 (installed here with
  `sdkmanager` from dl.google.com). The owner's Codex status records that the initial clean build took 16 min 25 s.
  In this container the first Gradle run hit HTTP 429 from Maven Central, so retry later or use the cache.

## 1. The owner's reports, read stage by stage

### 1.1 The 11 October LG V60 report [verified-report]

Phone LG LM-V600, Android 13 (API 33), LG UX. Svan 0.5.13-audiophile-preview, service up 82 s, enhanced reports
**off**, developer options on. Target Apple Music 6.5.3 (`com.apple.android.music`, uid 10778), installed from Play.

| Stage (`diag/Pipeline.kt`) | Result | Reading |
|---|---|---|
| D1 service running | PASS | |
| D2 broadcasts reach Svan | PASS | Self-test broadcast delivered in 26 ms, so receiving works |
| D3 player announced an audio session | **FAIL** | 0 OPEN and 0 CLOSE broadcasts from Apple Music (`DiagReport.kt:214`; counts are since process start) while Android reports 1 active media player |
| D4 to D8 | skip | nothing accepted, routed, attached or verified |
| D5 enhanced reports list sessions no announcement covers | N/A | reports are off |
| C1 audiophile engine running | FAIL | expected: `CapturePolicy.startupBlock` refuses without a routed per-app session when reports are off (`CapturePolicy.kt:6-11`) |
| C2 to C13 | skip | |
| C14 no restarts or kills | WARN | the previous run was killed while capturing (LG UX battery policy) |

Findings raised by `DiagRules.kt`:
- **NO_ANNOUNCEMENTS_AT_ALL** (`DiagRules.kt:112-130`): "Players announce when they create a session, so restart the
  player. Svan cannot discover a session that already existed before its service started, except through Enhanced
  detection."
- **HIDDEN_PLAYER** (`DiagRules.kt:141-146`): "A playing app is hiding its audio session. Try 'Whole-phone EQ for
  hidden players' or shared-output EQ, or Enhanced detection."
- **PREVIOUS_RUN_KILLED** (WARN): battery restrictions. Not a code defect.

### 1.2 What the report proves, and what it does not [design reading]

Proves: Svan's receiver works; Apple Music did not announce a session to this service instance; Android sees one
active player that Svan cannot name.

Does not prove: that Apple Music announces when it is restarted after Svan starts (nobody ran that test); that Apple
Music's audio is capturable; that its output path is direct (no `BYPASS_PATH` finding was raised, so the path is unknown).

### 1.3 Why it sounds like "system effects" [hypothesis, strong]

With no per-app session, the only processing Svan can apply is the whole-phone mix fallback, `MixFallback`:
- It runs when all of these hold for 3 s: the whole-phone setting is on (default `true`, `EqModel.kt:412`), the EQ is on,
  the capture engine is not running, no shared-output EQ is requested, Android reports music active, an anonymous
  player is counted, and no Engine A or B route is playing (`MixFallback.kt:51`, `MixFallbackPolicy`).
- Its card text is "A player is hiding its audio connection, so your EQ is on the whole phone output for now
  (notification sounds included)" (`DetectionCard.kt:45-46`).

If that card appeared while Apple Music played, "system effects" means this fallback. Experiment E2 settles it.

### 1.4 Version note

The earlier 0.5.5 analysis is superseded. Some of its facts changed on 0.5.13: the verdict cache, the trim line numbers,
the privacy filter and the core. Use only the references in this brief.

## 2. Tickets

### P-1: Svan processes and lists messaging, calling and similar apps (owner request, 10 October 2026)

**Owner request:** hard-code Svan to ignore audio from messaging apps and anything else with privacy concerns. YouTube
and music platforms stay supported.

**State on 0.5.13 [verified-code]**
- `MusicSourcePolicy.utilityPackages` (`MusicSourcePolicy.kt:5-9`) lists only Rapido (passenger, rider, captain), system
  UI, settings, phone and face unlock. **WhatsApp, Instagram, Messenger, Telegram, Snapchat, Google Messages, dialers and
  similar apps are not listed.**
- `MusicSourcePolicy.exclusion()` (`:17-24`) excludes system-uid sessions, utility names, non-media usages, sonification
  and short soundpool sounds. A WhatsApp or Instagram **media** session passes it.
- The consumers, and what each one checks:
  - Display, after `exclusion()` or by name: `PlaybackSessions.kt:224` (`mediaSessions`), `DetectionMonitor.kt:98-99`
    (`musicSessions`, `musicUnresolved`), `AudiophileScreen.kt:85` (`knownApps`, by name only), `DetectionCard.kt:146`
    (names), `DiagnosticReport.kt:83`, `MainActivity.kt:264`.
  - Routing with readable reports: `SessionRouter.sync` (`:630` `musicGate.admit`; `:635` releases excluded sessions;
    `:637` retains the gate state).
  - **Routing from the broadcast path, which has no category check:** `SessionRouter.sessionOpened` (`:366-367`) checks
    only `excludedPackage(pkg)`, the name from the intent. `:372` checks `exclusion(observed)` only when reports are
    readable. `openOnWorker` (`:394`) and `reroute` (`:683`) consult neither the gate nor any category. So an app that
    broadcasts OPEN is routed unless its name is in the 5-line utility list.
  - `MusicSourceGate.admit` (`MusicSourcePolicy.kt:30-44`): an unknown media session is admitted after 1.5 s of
    "started" with the same uid and package. `immediate()` (`:25-26`) admits at once for `CONTENT_TYPE_MUSIC`,
    `musicPackages` (YouTube, YouTube Music, Apple Music, Gaana and others) or `USAGE_GAME`.
- **Whole-phone fallback (`MixFallback`) cannot tell apps apart.** While it runs it equalises the output mix, messaging
  audio included. See P-2.
- **Limit that cannot be removed without reports:** on the broadcast path the package name is the sender's claim.
  `SessionAnnouncement.consistent` checks it against the audio service's uid only when reports are readable. So the
  ignore list is enforced by name there. This is a [design] limit to state to the owner.

**Expected behaviour (acceptance)**
1. An ignored app is never attached, captured, listed or routed, by name or by uid, on either path (broadcast or report).
   It is never admitted to Engine B and never receives an Engine A effect.
2. The list is hard-coded, categorised and tested. Changing it is an owner decision (`AGENTS.md` line to add).
3. YouTube, YouTube Music, Apple Music, Spotify, Gaana, Amazon Music, Tidal, Deezer, SoundCloud, Neutron and VLC are not
   ignored (a test pins each). They stay in `musicPackages`.
4. A uid-only session (`uid:NNNN`) is resolved with `getPackagesForUid`, which returns an array. The uid is ignored if
   **any** package on it is ignored. A session whose package cannot be resolved is not decided by name alone.

**Implementation touchpoints** (0.5.13 line numbers)
- `MusicSourcePolicy.kt`: replace `utilityPackages` with a categorised, hard-coded set (exact names and dot-terminated
  prefixes). Keep the system-uid and sonification rules. Candidate data: `docs/handoff/privacy_ignore_candidates.tsv`.
- `SessionRouter.kt:366-372` (`sessionOpened`): check the ignore list on the claimed package and, when readable, on the
  observed package. `:394` (`openOnWorker`) and `:683` (`reroute`): refuse ignored sessions before any route or effect.
- `PlaybackSessions.kt:224`, `DetectionMonitor.kt:98-99`, `AudiophileScreen.kt:85`, `DetectionCard.kt:146`: the same
  rule, so the display and routing agree.
- `MusicSourcePolicyTest.kt` plus a new router-level test for the broadcast path (see §4).

**Tests to add (JVM)**
- Messaging, social, ride, payment, assistant, recorder, camera and telephony samples: `exclusion(s) != null` and both
  `MusicSourceGate.admit` and the broadcast check refuse them. Include a session with `CONTENT_TYPE_MUSIC` and one with
  `USAGE_GAME`, so `immediate()` cannot bypass the list.
- YouTube, YouTube Music, Apple Music and the other protected platforms: not excluded, and `immediate()` true for those
  in `musicPackages`.
- A uid shared by an ignored and an allowed package: the uid is ignored.
- Lookalikes such as `com.whatsappy.app` and `com.facebookish.player` are not matched.

**Known gaps to report, not silently fix**
- Rapido is already excluded by name. If it still appears on a phone, its session is probably unresolved (`uid:`) or it
  uses a package name outside the list. Check it in E4 before changing the list.
- Name matching is the only check on the broadcast path without reports (see the limit above).

### P-2: The whole-phone fallback equalises messaging audio [design, owner decision]

`MixFallback` applies to the output mix. A messaging app's audio on that output is equalised while it runs, and the card
says notification sounds are included. The ignore list cannot reach it. Options for the owner:
- **(a)** Suspend the fallback during calls (`AudioManager.getMode()` is in-call or in-communication), and whenever a
  started session of an ignored app is observed (only possible with reports on, because without them Svan cannot tell
  which app is playing). This is partial and says so.
- **(b)** Default `wholeMixFallback` to off (`EqModel.kt:412`) and keep the explicit switch with an accurate disclosure.
- **(c)** Remove the fallback.

Recommendation [design]: (a) and (b) together, and the card copy must say messaging and call audio on the output may
also be equalised when the fallback is on. The owner decides.

### M-1: Apple Music is not processed per app on this phone [investigation first; no code until E1 to E3 are run]

**State [verified-report, verified-code]:** D3 FAIL, HIDDEN_PLAYER and C1 FAIL. Per-app routing needs a session id, which
comes from a broadcast after the service starts or from the reports (Music detection by DumpGrant or Shizuku). Without
either, Svan has only the anonymous count (`publicActiveCount`) and can apply only `MixFallback` (§1.3).

**Decision tree (the owner runs E1 to E3 first; §3)**
- **E1 shows an OPEN from Apple Music after a fresh start with the service running:** D3 passes. Check D4 to D8 in the new
  report. Apple Music is in `musicPackages`, so admission is immediate when reports are readable. Engine B or A then
  follows the capture verdict. No code change is expected unless a later stage fails; report that stage.
- **E1 shows no OPEN:** Apple Music does not announce on this phone. Per-app processing needs Music detection (reports).
  Copy change, if the owner agrees: the HIDDEN_PLAYER and C1 text should say what is possible, for example "Apple Music is
  playing but does not tell Svan its audio session. Turn on Music detection for per-app processing; the whole-phone EQ
  is the only other option."
- **E3 (reports on) shows Apple Music routed to Engine A with a stream or UID opt-out finding** (`STREAM_OPTS_OUT`,
  `UID_POLICY_BLOCKS_CAPTURE`, `MANIFEST_DISABLES_CAPTURE`): that is the app's capture policy. The UI already says so.
  Do not force capture.
- **E3 shows a bypass path** (`BYPASS_PATH`): the owner's Apple Music lossless or hi-res setting may be the cause. Test
  it (E3b). Do not claim a cause until it is measured.

**Do not change the verdict cache.** BLOCKED is no longer written permanently: `SessionRouter.kt:777` ("no permanent or
app-version BLOCKED verdict is written"), `CaptureCompat.kt:22-23` (`INCONCLUSIVE` added), and the per-version
`CaptureVerdictKey` and `SilentStrikes` described in `DETECTION_FALLBACKS.md` ("8 October 2026").

**Copy to fix, whatever E1 shows:** `CapturePolicy.kt:10` says "keep your song playing", which is wrong when the player
never announces. Candidate: "Restart the player after Svan starts, or turn on Music detection."

### M-2: YouTube stays a platform [verified-code, guard only]

YouTube (`com.google.android.youtube`) and YouTube Music (`com.google.android.apps.youtube.music`) are in `musicPackages`
and are not in the utility list. A test pins them as not ignored, so the P-1 change cannot remove them. The owner's
"watching a live performance on YouTube with Svan processing in the background" is the case this protects.

### L-1: The Lab and Svaresa loudness trim dips and returns (owner report, 10 October 2026)

**State on 0.5.13 [verified-code]**
- Immediate path: `refreshEq()` (`Svaramanas.kt:377`) calls `requestRecompute(immediate = true)`. `recompute` (`:385`)
  then sets `drifted = target` at once (`:438`). Callers: `SvanRepository.kt:141,160,167,171`, which include Svaresa band
  faders. Each fader move therefore steps the trim.
- The trim is overwritten, not slewed: `val next = drifted.copy(preampDb = (-delta).coerceIn(-18.0, 1.5))` (`:451`).
  `slew()` (`:465`) limits `preampDb` (`:477`), and its result is replaced. The class comment at `Svaramanas.kt:174`
  promises "Adaptive updates slew at most 0.5 dB per band every 3 s". The trim breaks that promise.
- Source switch: `predictedGuideLoudnessDeltaDb` uses a pink reference unless `features->valid`
  (`core/src/svaramanas.cpp:170-172`, `:185`). `valid` becomes true after 3 s of audio (`core/src/analyzer.cpp:268`).
- The analysis is read only while Engine B captures (`Svaramanas.kt:401`). In system-effects mode the trim never switches
  to a measurement.

**Probe [verified-probe]:** the trim formula is unchanged between the 0.5.5 and 0.5.13 cores (identical output).
Predicted trim for a quiet-listening layer, with a synthetic tilt as the music stand-in (not a recording):

| Quiet-listening lift | Pink estimate, applied at once | Measured at 3 s, −4.5 dB/oct | Change at 3 s |
|---|---|---|---|
| bass 2 dB, treble 1 dB | −0.46 dB | −1.46 dB | −1.00 dB |
| bass 4 dB, treble 2 dB | −0.99 dB | −3.02 dB | −2.03 dB |
| bass 6 dB, treble 3 dB | −1.59 dB | −4.65 dB | −3.06 dB |

Every synthetic tilt tested (−3, −4.5, −6 dB/oct) moves the trim further down, because those spectra carry more bass than
pink. **[hypothesis]** A rise after apply, as the owner heard, would mean the owner's material has less energy in the
boosted bands than pink. Confirm with logs before tuning (§2 L-1 step 4).

**Not measured:** the phone, the output level after protection, Bluetooth, or the Android mixer. The numbers are predicted
offsets of the trim only.

**Fix options (for the coder; owner chooses)**
1. Route the trim through the band limits on every path, including `immediate`. Proposed rate: 1 dB per second in either
   direction, so a 3 dB change takes 3 s.
2. Replace the switch at 3 s with a confidence ramp: estimate until valid, then blend toward the measured trim over about
   10 s of analysis.
3. In system effects, label the trim as an estimate in the UI.
4. Log `delta`, `valid`, `seconds` and `preamp` on each update, so a listening session can be matched to numbers.

**Acceptance (tests to add):** a layer change moves the applied level by at most 0.2 dB per 100 ms; the level reaches its
final value within 6 s with no overshoot beyond 0.2 dB; blind-listening level matching still holds. These checks show the
trim is continuous. They do not show that it sounds better. The owner listens (E6).

**Plan:** `docs/LAB_INTEGRATION_PLAN.md`, updated for 0.5.13 in this commit.

### L-2: Lab CPU cost and the opt-in decision [design; measured on host]

Measured with `docs/handoff/probes/lab_cost.cpp` on the 0.5.13 core, two runs, host, 12 bands, stereo, 48 kHz, 10 s each
(percent of one host core):

| Switch | Efficient (1x) | Audiophile (4x) | Read-out |
|---|---|---|---|
| Engine only | 4.0% | 7.0% | 1.6 to 2 times the 0.5.5 figure (1.9% and 4.5%); the core changed, so these are not like-for-like |
| Gain protection | within noise | within noise | keep on |
| Auto headroom | within noise | +0.8 (one run; noisy) | keep on |
| Selective dynamic EQ | +1.5 | +1.8 | largest add-on; no listening evidence in the repo |
| Engine B analysis | +0.1 to +0.4 | +0.5 | needed for a measured trim; capture only |
| Svaresa full set | +1.4 to +1.8 | +1.7 | dynamic EQ is most of it |

**Decision [design]:** make selective dynamic EQ opt-in until blind listening shows a preference, because its thresholds
are documented as awaiting listening evidence (`docs/QUALITY_LAB_0.5.5.md`). Phone CPU and battery are not measured here.

## 3. Owner experiments (no code)

Do these in order on the LG V60. Keep the detailed report each time (Hi-Fi → Music detection → Share detailed report).

- **E1 (does Apple Music announce?)**, reports off. Force-stop Apple Music. Start Svan and its system equalizer, and
  confirm the service is running. Only then start Apple Music and play for 20 s. Share the report. Note the
  "announcements from com.apple.android.music" line. Any OPEN means D3 passes; no OPEN means it does not announce to a
  service started before it, or at all.
- **E2 (is "system effects" the whole-phone fallback?)**, reports off. Turn on Hi-Fi → Music detection → "Whole-phone EQ
  for hidden players" and make sure EQ is on. Play Apple Music with nothing else playing. Does the card read "A player is
  hiding its audio connection … on the whole phone output"? If yes, the sound you heard is the fallback.
- **E3 (per-app processing with reports on)**, Music detection enabled by DumpGrant (or Shizuku). Repeat E1. Record the
  Apple Music row in Hi-Fi (its engine label, or "Capture blocked" text) and any finding from §2 M-1.
- **E3b (bypass check)**, optional. If E3 shows a bypass path, switch Apple Music's lossless or hi-res option off, repeat
  E3, and record the path label.
- **E4 (privacy reproduction)**, with reports on and then off. Play a WhatsApp voice note or an Instagram reel for about
  10 s, using your own content. Record whether the app name appears in Hi-Fi "Android sees", in the Apps and engines list,
  and whether a `route: com.whatsapp` or `route: com.instagram…` line appears in the report. Repeat with Rapido. This
  tests P-1 and P-2. Nothing else is shared.
- **E5 (battery, to clear PREVIOUS_RUN_KILLED)**. Set Svan to unrestricted battery use and lock it in recents. Repeat E1.
  The C14 warning should clear.
- **E6 (listening, Lab dip)**. Only after L-1 is changed: level-matched A/B on the same track, old against new trim, and
  dynamic EQ on and off. The owner records dip size, time to settle and preference. The app does not infer preference.

## 4. Tests and checks for the coder

- **Core (0.5.13), from a full checkout:** `cmake -S core -B build -DEQCORE_BUILD_TESTS=ON && cmake --build build -j4 &&
  ./build/eqcore_tests`. The tests read `android/app/src/main/assets/lab/` and the Kotlin test sources through
  `EQCORE_REPO_DIR`, so run them from a full checkout. From a `core/`-only export the lab model test dereferences an empty
  vector at `core/tests/test_main.cpp:4238` (`lab_wola_matches_all_six_published_models_and_reference_bass_blend`), and
  four policy checks report missing Kotlin files. Both are artifacts of the partial export (verified here with ASan and
  UBSan). **Full-checkout result at `fbe0680`: 215 tests, 0 failed checks, exit 0** (default CMake Release build in this
  container, GCC 13.3). ASan and UBSan have not been run on the full tree.
- **Android:** `cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest`, with
  `ANDROID_HOME` set. Not run in this pass.
- **Manifest policy:** `python3 android/scripts/check_manifest_permissions.py <compiled manifest dump>`.
- **Probes:** `docs/handoff/probes/svaresa_trim.cpp` and `docs/handoff/probes/lab_cost.cpp`. Compile with
  `g++ -std=c++17 -O2 -I core/include core/src/*.cpp <probe>.cpp -o <probe>`. Both compile against the 0.5.13 core.

## 5. Do not fix (checked and correct, or already fixed)

- The verdict cache (`SessionRouter.kt:777`, `CaptureCompat.kt:22-23`). Permanent BLOCKED writes are gone.
- `CapturePolicy.eligibleUids` admits only muted, known UIDs. Do not widen it.
- `MixFallback`'s 0.3 s fade and its 10 s rule against double processing (`DETECTION_FALLBACKS.md`).
- `SessionAnnouncement.consistent`. It rejects claims that name another app's uid when the audio service disagrees.
- The trim formula in `predictedGuideLoudnessDeltaDb`. The fault is the update rule (L-1), not the formula.

## 6. Paste-ready entry for `docs/audio-quality/REQUESTS_FROM_CLAUDE.md` (Kotlin lane)

Append this only after the owner confirms the target branch. Do not edit `docs/audio-quality/**` from this branch.

```
## 11 October 2026: privacy ignore list, Apple Music state, Lab trim (Claude, Kotlin lane requests)

1. P-1 (privacy, owner request). Make MusicSourcePolicy's ignore list categorised and hard-coded (messaging, calls,
   social video, ride and delivery, payments, assistants, recorders, cameras, telephony). Apply it on the broadcast path
   (SessionRouter.sessionOpened and openOnWorker, reroute) as well as in the report path, and resolve uid-only sessions to
   every package on the uid. Protect YouTube, YouTube Music, Apple Music, Spotify, Gaana and the other platforms. Tests in
   MusicSourcePolicyTest. Candidate data: docs/handoff/privacy_ignore_candidates.tsv.
2. P-2 (owner decision needed). The whole-phone fallback (MixFallback) equalises messaging audio. Owner chooses (a) suspend
   during calls and when an ignored app is seen playing, (b) default off, or (c) remove.
3. M-1 (Apple Music). Do not change code until the owner's experiments E1 to E3 are in. Then follow the decision tree in
   HANDOFF_BRIEF_2026-10-11.md §2 M-1. Replace "keep your song playing" in CapturePolicy's start message.
4. L-1 (Lab trim). Svaramanas recompute: apply the trim through the same limits as the bands, including immediate. Replace
   the single switch at 3 s with a confidence blend. Keep the probe numbers in §2 L-1 as the baseline.
```

## 7. Decisions the owner needs to make

1. The implementation branch: `ccr-f8964344-8f7mf5`, or another. This branch is 0.5.5, and the request file lives on the
   0.5.13 line.
2. P-2: how the whole-phone fallback should treat messaging and call audio (options in §2 P-2).
3. Whether Apple Music must be processed per app on this phone. That needs Music detection. The whole-phone EQ is the
   only permission-free option.
4. L-1: after a change in the applied level, should the level return to its previous value (loudness-neutral), or settle
   at the new trimmed level?
5. L-2: make selective dynamic EQ opt-in?

## Appendix: what was checked, and where

- Owner report: pasted in the 11 October 2026 session, read in full.
- Code: `ccr-f8964344-8f7mf5` at `fbe0680`, read with `git show` and `git grep`. Nothing was checked out or pushed there.
- Probes: `docs/handoff/probes/` (run against the 0.5.13 core on this container).
- Core test run at `fbe0680`: 215 tests, 0 failed checks on a full checkout (§4). The earlier partial-export runs (no lab
  assets, no Kotlin test files) are artifacts and are not findings.

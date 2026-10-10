# Opus build brief: Svan 0.5.13 after the recording removal (11 October 2026)

Status: **built as 0.5.14 (code 21)** on `ccr-f8964344-8f7mf5`, 11 October 2026. The owner approved the recommendations,
including the Engine A trim default, and let the builder decide where needed. Not yet heard on a phone. Line numbers below
were read before the build and have moved.

| WP | Built | Where it differs from this brief, and why |
|---|---|---|
| WP-1 ignore list | `MusicSourcePolicy.privateCategory`, router `refusePrivate`, cached uid resolver | The "verify" rows are included: a wrong package name never matches anything, and every row is in a category the owner asked to block. |
| WP-2 defaults | Defaults revision 2 stored inside the saved JSON | Keyed inside the JSON, not a separate flag, so restored backups from older versions reset too. The fallback also yields while ringing. |
| WP-3 trim | `TrimRamp`, blended target, Engine A off unless opted in | The ramp is asymmetric: attenuation ramps at 1 dB/s, removal is immediate, so the level is never quieter. New: under Svaresa, Hi-Fi forced Auto headroom, which set the gain to −(largest boost) and overrode the trim (a +4 dB bass boost left the track about 1–3 dB quieter than matched). "Keep my level" (on by default) lets the true-peak limiter guard Svaresa's peaks on Engine B instead; system effects keep static headroom. |
| WP-4 Lab Tools | `ui/LabTools.kt`, first page of the Lab | Readouts poll four times a second through a new non-allocating JNI fill (`nativeProcessorReadoutsInto`) into double buffers. Selective dynamic EQ has a switch here as well as in the Svaresa panel; both write the same setting. |
| WP-5 declutter | `DisclosureGroup`; Hi-Fi first view: Engine, Apps & engines, Background equalizer, Quality and output, Advanced | No settings sheet (an owner choice still open). |
| WP-6 | No change | |

This brief replaces `docs/CODEX_HANDOFF_2026-10-11.md` (its R-1 is done, its R-2 is moot, and its L-2 and U-1 assumed the
Lab tab would go, which the owner has reversed). The longer background is in `docs/handoff/HANDOFF_BRIEF_2026-10-11.md`.

Labels: **[verified-code]** read in the branch. **[verified-probe]** measured on the host. **[owner-report]** from the
owner. **[design]** a product choice. **[proposed]** my recommendation, not yet approved by the owner.

## 0. Ground rules

1. Branch `ccr-f8964344-8f7mf5` only. Fast-forward pushes. No force-push. No PR, release or publish unless the owner asks.
2. The owner asked for this build, so the Kotlin, Android, scripts and CI lane is yours for this work, whatever
   `docs/audio-quality/COORDINATION.md` says about Codex. Log each work package in `docs/audio-quality/CODEX_STATUS.md`.
   Core C++ and the JNI C++ stay with Claude: if you need a core API, add a dated entry to
   `docs/audio-quality/REQUESTS_FROM_CODEX.md` and carry on with what does not depend on it.
3. `AGENTS.md` binds you. Where it conflicts with §2, §2 wins.
4. No notification listener, SMS or accessibility capability. `android/scripts/check_manifest_permissions.py` must pass.
   Recording stays removed: write, save or export no captured audio anywhere, not even a temporary file. Add no permissions
   and no storage access.
5. Audio thread: no allocation and no waiting (AGENTS rule 3). Read native values on the capture thread and publish copies,
   as `unmaskSnapshot` does (`CaptureService.kt:550`, accessor `:722`). The UI never calls a DSP getter directly.
6. Design: AGENTS rule 4. Gold on warm charcoal, jewel accents only for their given meanings, no new hues. The brand line
   stays on every screen. The EQ title stays "EQ exten9ed".
7. Privacy and permissions docs are owner-decision-only. WP-1 and WP-2 need wording changes there; the recorded basis is the
   owner's 10 October request and D3 below. Keep the wording plain and ask the owner to read it before release.

## 1. What is already done (do not redo)

| Item | State | Evidence |
|---|---|---|
| R-1 recording removed | Done in `d454d22`. Proof recorder, clip tap and player, blind listening and WAV import, recording evidence, `blind_lab` and `proof_*` commands, recording CI steps and docs are gone. | Local debug compile and 428 JVM tests pass. CI not yet run on it. |
| Launch cleanup | `MainActivity` deletes `filesDir/proof`, `recording-evidence.json` and the `blind-listening` preferences on a background thread. | Not tested on a device. |
| Core Lab readouts | `ec02227`. `Engine::analogTopReductionDb`, `expressionGainDb`, `shrillReductionsDb`, `bassDetailLiftDb`, and `nativeProcessorReadouts` in the JNI bridge. | Core: 218 tests, 0 failed. JNI syntax-checked with the NDK compiler. |

Kept on purpose: `QualityLab` and `BlindRenderer` (synthetic signals for the release checks), the `WavClip` type
(no file reading), and `WavWriter` (a generated 20-second measurement WAV with no captured audio).

## 2. Owner decisions (binding)

- **D1 Branch.** `ccr-f8964344-8f7mf5`.
- **D2 Apple Music, Gaana, Spotify.** [owner-report] Apple Music works per app again after an app update, and Gaana did the
  same earlier. No special handling for either. Spotify refuses Svan: show it as blocked by the app, with the existing copy.
  Do not work around any refusal.
- **D3 Whole-phone fallback.** Default off. Its name is not visible anywhere a user can see it. Reason: "people freak out
  needlessly".
- **D4 Loudness.** The processed sound stays at the level the listener had, at any listening volume. Up to +0.1 dB louder is
  acceptable; quieter is not. Choose the smoothest workable method (WP-3).
- **D5 Lab.** The Lab tab **stays**. Its current contents belong there because they are the machinery of the sound. It gains
  more tools, and every tool must work: "not just for show". Bass detail and Highs experimental are the first two. Selective
  dynamic EQ becomes opt-in (default off).
- **D6 Recording.** Removed entirely (done). The app is published from a website and the owner will not risk piracy claims.
- **D7 Declutter.** Sleek and organised, with full control kept for power users.

## 3. Work packages, in this order

Take them in order. WP-4 needs WP-3 only for its opt-in "Estimated level match" row, and WP-5 goes last because it reshuffles
screens that WP-2 and WP-4 also touch.

### WP-1 Ignore list for messaging, calling and privacy-sensitive apps (owner request of 10 October)

**[verified-code]**
- `MusicSourcePolicy.kt:5-9` (`utilityPackages`) lists only Rapido (passenger, rider, captain), system UI, settings, phone and
  face unlock. WhatsApp, Instagram, Messenger, Telegram, Snapchat, Google Messages and dialers are not listed.
- `exclusion()` (`:17`) drops system-uid sessions, utility names, non-media usages, sonification and short soundpool sounds.
  A WhatsApp or Instagram media session passes it.
- `immediate()` (`:25-26`) admits at once anything with `CONTENT_TYPE_MUSIC`, a name in `musicPackages` (`:10`) or `USAGE_GAME`.
  `admit()` (`:33`) admits an unknown media session after 1.5 s.
- The broadcast path has no category check. `SessionRouter.sessionOpened` (`:366`) only checks `excludedPackage(pkg)` (`:367`),
  the name the sender claims. `:372` checks `exclusion(observed)` only when audio reports are readable. `openOnWorker` (`:394`)
  and `reroute` (`:683`) consult neither the gate nor a category.
- Display paths that must agree with routing: `PlaybackSessions.kt:224`, `DetectionMonitor.kt:98-99`,
  `AudiophileScreen.kt:62` and `:79` (`knownApps`), `DetectionCard.kt:146` (`names`).
- Limit to state to the owner, not to fix: on the broadcast path without readable reports, the package name is the sender's
  claim, so the list is enforced by name there. `SessionAnnouncement.consistent` checks it against the audio service's uid
  only when reports are readable.

**Build**
1. Replace `utilityPackages` with a categorised, hard-coded set: exact names and dot-terminated prefixes. Categories:
   messaging and calls, social video, ride and delivery, payments, assistants, recorders, cameras, telephony. Keep the
   system-uid and sonification rules. Candidate data (87 rows, with confidence): `docs/handoff/privacy_ignore_candidates.tsv`.
   Rows marked "verify" must be confirmed on a phone before they are hard-coded; leave them out and list them in
   `CODEX_STATUS.md` if you cannot confirm them.
2. Apply the list on every path: `sessionOpened` (claimed and observed package), `openOnWorker`, `reroute`, `sync`, and every
   display path above. An ignored app is never attached, captured, listed or routed, by name or by uid.
3. A uid-only session (`uid:NNNN`) is resolved with `getPackagesForUid`. The uid is ignored if **any** package on it is ignored.
   A session whose package cannot be resolved is not decided by name alone.
4. Protected platforms are never ignored, and a test pins each: YouTube, YouTube Music, Apple Music, Spotify, Gaana, Amazon
   Music, Tidal, Deezer, SoundCloud, Neutron and VLC. The owner's case: watching a live performance on YouTube with Svan
   processing in the background.
5. Add an `AGENTS.md` line: changing the list is an owner decision. State the list in plain words in `docs/PRIVACY.md` (§0.7).

**Acceptance (JVM)**
- Each category: `exclusion(s) != null`, and both `MusicSourceGate.admit` and the broadcast check refuse it. Include a session
  with `CONTENT_TYPE_MUSIC` and one with `USAGE_GAME`, so `immediate()` cannot bypass the list.
- Each protected platform: not excluded, and `immediate()` true for those in `musicPackages`.
- A uid shared by an ignored and an allowed package is ignored.
- Lookalikes `com.whatsappy.app` and `com.facebookish.player` are not matched.

### WP-2 Fallback and dynamic EQ: new defaults, one migration (D3, D5)

**[verified-code]**
- `EqModel.kt:412` defaults `wholeMixFallback` to `true`; it is saved at `:459` and read at `:473` with default `true`.
- `ui/DetectionCard.kt:45-51` shows the text "A player is hiding its audio connection … (notification sounds included)" and the
  switch "Whole-phone EQ for hidden players".
- `diag/DiagRules.kt:144` advises "Try 'Whole-phone EQ for hidden players' …".
- `svaramanas/Svaramanas.kt:73` defaults `selectiveEq` to `true`, read at `:91` with default `true`. The switch is in the Svaresa
  panel (`ui/SvaramanasPanel.kt:209`). `recompute` turns it into `dynamicEq` at `:419`.

**Build**
1. Both defaults become `false` for new installs.
2. Existing installs saved the old defaults as if they were choices. Add one migration, keyed by a stored revision number, that
   sets both to `false` once and records that it ran. It cannot tell a real choice from the old default, so say so in the
   `CODEX_STATUS.md` entry. Choices made after the migration are kept. Test: a saved JSON with both `true` and no revision
   migrates to `false` once; the same JSON with the revision present is left alone.
3. Remove the card text and the switch from the UI. The name must not appear anywhere a user can see it, including the
   shareable report. **[proposed]** Offer no control at all. Keep the `mix_fallback` debug command for tests.
4. Rewrite the `DiagRules` advice without the name and without "hidden player" alarm wording.
5. **[proposed]** Suspend the fallback during calls (`AudioManager.getMode()` in call or in communication).
6. Selective dynamic EQ keeps its switch in the Svaresa panel (one control, no duplicate). WP-4 shows its live state.

**Acceptance (JVM):** new and migrated installs start with both off, and neither turns on by itself. Grep gate: none of
"whole-phone", "hiding", "hidden player" or "notification sounds included" in UI strings.

### WP-3 Loudness trim: smooth, matched, never quieter (D4)

**[verified-code] today.** `Svaramanas.recompute` computes `drifted` at `:438` (the bands and preamp are slewed unless
`immediate`), then `:449` computes `delta` with `nativeSmartLoudnessDelta`, and `:451` writes
`val next = drifted.copy(preampDb = (-delta).coerceIn(-18.0, 1.5))`. That discards the slewed preamp, so the trim steps.
`refreshEq` (`:377`) takes the immediate path. Analysis becomes valid after 3 s (`core/src/analyzer.cpp:268`); the pink
reference is at `core/src/svaramanas.cpp:170-172`. The delta already switches from the pink estimate to the measured spectrum
when the analysis turns valid, which is the step the probe shows (−0.99 dB to −3.02 dB for a +4 dB bass and +2 dB treble
layer, `docs/handoff/probes/svaresa_trim.cpp`; predictions, not measurements of a recording).

**Method.** A measured trim blended from the pink estimate, rate-limited, with a loudness-neutral target. Slewing the current
step still dips, and an estimate-only trim is 1 to 2 dB off.
1. `trim_est` = −predicted(pink features): the existing predictor with no features.
2. `trim_meas` = −predicted(measured features) once the analysis is valid. Engine B only.
3. `w` = clamp((seconds_valid − 3) / 10, 0, 1); 0 while the analysis is not valid.
4. `target` = (1 − w)·`trim_est` + w·`trim_meas` + 0.05 dB. The bias is deliberate: slightly above neutral.
5. `applied` moves toward `target` by at most 1.0 dB per second, updated every 100 ms, and is the only preamp value sent to the
   engine. Band edits still apply at once; only the trim is rate-limited. "Lag, don't lead": the level may be briefly louder
   while the trim catches up, never quieter.
6. Keep the Svaresa band logic and the SLEW constants. `immediate` must not write the trim in one step.
7. Put the ramp in a pure class, for example `TrimRamp.step(applied, target, dtSec, ratePerSec)`, so the JVM can test it.

**Engine A (system effects). [proposed, owner undecided]** The estimate cannot honour "never quieter" (it is 1 to 2 dB off, and
Engine A has no measurement). Recommendation: the trim is **off on Engine A by default** (the layer's preamp is 0 dB there), with
an opt-in switch "Estimated level match" in the Lab Tools page (WP-4) that uses `trim_est` through the same ramp and labels it
"estimated", with no 0.1 dB claim. Keep the default in one named constant so the owner can flip it after listening.

**Acceptance**
- Pure ramp: never moves faster than rate × dt, never overshoots, converges within 12 s for a change of up to 6 dB.
- Step test (host or emulator): after a +6 dB bass-shelf change on a steady signal, the K-weighted level in 400 ms blocks never
  falls more than 0.1 dB below its level before the change; after 12 s it sits within [0, +0.1] dB of the bypass level.
- Predictor check before merge: compare the predicted delta with the measured K-weighted difference (`nativeMatchComparison`) on
  synthetic signals and on owner WAVs that are not shared. If the error exceeds 0.1 dB on material that matters, stop and file a
  core request for a closed-loop output loudness meter.

### WP-4 The Lab: keep the tab and add tools that work (D5)

**[verified-code] today.** The Lab is `ui/SvanApp.kt:125` → `LabScreen` → `IntegratedLabPanel` (`ui/IntegratedLabScreen.kt`) with
pages "Shape", "Engine" and "Measure" (`:76`): the reference-model fit, assumed output rate and FFT block, the measurement WAV
and report export, and "Device probes and engine log". Keep all of it. In Hi-Fi (`ui/AudiophileScreen.kt`) these experimental
sections exist, each marked "Audiophile engine only": Experimental bass unmasking (`:244`), Bass detail (`:251`), Highs (`:263`)
and Selective dynamic EQ (`:303`, text only).

**Build**
1. Add a first page "Tools" to the Lab (pills: Tools, Shape, Engine, Measure) and open on it.
2. Move these sections from Hi-Fi to Tools. Move the code, not copies, and keep every settings field unchanged, so there is no
   migration for them:

| Tool | Setting | Live readout |
|---|---|---|
| Experimental bass unmasking | `experimentalBassUnmask` | existing `bassUnmaskDiagnostics()` (four cuts and the note) |
| Bass detail: Attack definition | `bassAttack` | attack lift, dB (cap 2) |
| Bass detail: Sustain | `bassSustain` | sustain lift, dB (cap 3) |
| Bass detail: Dimension, Tube colour | `bassDimension`, `bassTube` | none: steady effects. Label them "always on while a bass note plays". |
| Highs: Analogue top | `analogTop` | reduction, dB (0 to −2.5) |
| Highs: Expression | `expression` | signed gain, dB (up to about 1.4 dB peak to peak; 0.91 seen in the core test) |
| Selective dynamic EQ (read-only row; the switch stays in the Svaresa panel) | `selectiveEq` | existing `NativeEngine.dynamicReductions()` (120, 330, 3000, 6500 Hz) |
| Shrill guard (read-only row; Svaresa drives it) | none | presence and sizzle reductions |
| Estimated level match on system effects (WP-3, opt-in) | new, default off | the applied trim |

3. Each tool shows one honest status line: **Off**, **Waiting for Hi-Fi** (switch is on but Engine B is not running or the app
   is capture-blocked; the setting still saves and takes effect later), or **Active** with its live number. Never show "Active"
   without a number from the engine. Update about four times a second while the page is visible (`ObserveWhileVisible`).
4. Wire the readouts. `NativeEngine.kt` needs `@JvmStatic external fun nativeProcessorReadouts(handle: Long): DoubleArray` (next to
   `nativeBassUnmaskDiagnostics`, `:186`) and `fun processorReadouts()` (next to `:77`). The array order is fixed:
   `[0] analogTop (<= 0), [1] expression (signed), [2] shrill 4 kHz presence (<= 0), [3] shrill 8 kHz sizzle (<= 0),
   [4] bass attack lift (>= 0), [5] bass sustain lift (>= 0)`. Snapshot it on the capture thread beside `unmaskSnapshot`
   (`CaptureService.kt:550`) and expose a copy at `:722`. Add a JVM test that pins the index order against a fake.
5. Keep the existing "Not yet listening-tested" wording. Do not claim these sound better.
6. **[proposed, optional]** A momentary "Hear without Lab tools" button that bypasses the tool depths without changing the saved
   settings. Skip it if it needs more than a small change to the capture loop.

**Acceptance:** Hi-Fi no longer contains the four moved sections (grep gate on the section labels). Every former Lab control
still works. Each tool's switch changes its setting and its status line, and its readout moves on a test tone on the emulator
(for example 8 kHz at −6 dBFS for Analogue top). The Lab tab still exists (JVM test on the tab list).

### WP-5 Declutter, keeping full control (D7) [proposed structure; do last]

**[verified-code] today.** Five tabs (`SvanApp.kt:120-126`: Sound, EQ, Presets, Hi-Fi, Lab). Hi-Fi has 14 section headers
(`AudiophileScreen.kt`); WP-4 takes four of them out, leaving 10: Engine, Apps & engines, Background equalizer, Spatial processing,
Capture rate, Capture processing quality, Float output & optional dither, Gain staging, System effects resolution, Signal path.

**Proposed**
- Keep five tabs. First view of Hi-Fi shows at most six headers: Engine, Apps & engines, Background equalizer, plus two
  collapsed groups, "Quality and output" (capture rate, spatial, processing quality, float output and dither, gain staging) and
  "Advanced" (system effects resolution, signal path, readings). Each group's summary line shows its current values.
- Merge the duplicate status cards (`DetectionCard`, `CaptureStatusCard`, the Engine row) into one status strip.
- Optional: a settings sheet behind a gear icon for Diagnostics, Export and restore (now at `PresetsScreen.kt:128-129`), Music
  detection setup and About. Only if the owner confirms it (§6).
- Rules: progressive disclosure for everything else, one gold accent for the primary action, no new hues.

**Acceptance:** nothing is removed except what R-1, WP-2 and WP-4 name. Every control is reachable within two taps. CI
screenshots at phone width, in light and dark, reviewed by a person (AGENTS: "look at the screenshots").

### WP-6 Apple Music, Gaana, Spotify (D2): no build

Review the Engine row copy only. The existing verdict-keying tests (`CaptureVerdictKey`) must still pass. Add no per-app special
cases.

## 4. Verification

- **Core** (Claude's; unchanged by you). From a full checkout: `cmake -S core -B build/core -DEQCORE_BUILD_TESTS=ON && cmake --build build/core -j4 && ./build/core/eqcore_tests`.
  Expect 218 tests, 0 failed. It needs the full checkout because the lab tests read assets and Kotlin sources.
- **Android:** `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug :app:assembleRelease`, with `ANDROID_HOME` set.
  Needs SDK 36, build-tools 36.0.0, NDK 27.0.12077973 and CMake 3.22.1. If Maven Central answers HTTP 429, wait and retry; it is
  a rate limit, not a build error. Do not use `--offline` on a fresh cache.
- **Manifest:** `python3 android/scripts/check_manifest_permissions.py <compiled manifest dump>`.
- **CI:** the emulator run and its screenshots. The recording step is gone from the three workflows, and
  `production_release.sh` still writes exactly 8 PASS lines (the CI gates count them).
- **Grep gates:** WP-1, WP-2 and WP-4 above, plus
  `git grep -n -e ProofRecorder -e ClipRecorder -e BlindListening -e "Recording mode" -e "Svan Proof"` returning nothing outside dated notes.

## 5. Do not

- Write, save or export captured audio anywhere, including temporary files.
- Add a notification listener, SMS, accessibility, storage or account permissions.
- Work around any app that refuses capture, Spotify included.
- Turn whole-phone output processing on by default, or name it in the UI.
- Reintroduce permanent BLOCKED verdicts (`SessionRouter.kt:777`).
- Widen `CapturePolicy.eligibleUids`, or change `SessionAnnouncement.consistent`, `MixFallback`'s 0.3 s fade or its 10 s rule.
- Change the DSP formula `predictedGuideLoudnessDeltaDb`. The fault is the update rule (WP-3), not the formula.
- Change the core C++ or the JNI C++. File a request instead.
- Open a PR, cut a release or publish, unless the owner asks.

## 6. Owner decisions still open

1. **Engine A trim.** Keep an estimate-only trim there, turn it off by default (recommended), or bias it louder? WP-3 builds the
   recommendation with a single constant to flip.
2. Fallback: no control at all (proposed), or a neutral control under Advanced?
3. Fallback: suspend it during calls (proposed)?
4. WP-5: confirm the grouping, and whether to add the settings sheet.
5. WP-4: confirm "Tools" as the Lab's first page, and the optional "Hear without Lab tools" button.

Nothing in WP-1 to WP-4 waits on these except the Engine A default in WP-3.

## Appendix: Kotlin and JNI pointers

- `NativeEngine.kt`: `bassUnmaskDiagnostics` `:77`, `setAnalogTop`/`setExpression` `:94-97`, `dynamicReductions` `:122`, externals
  `:163`, `:186`, `:202-203`.
- `CaptureService.kt`: `unmaskSnapshot` `:550`, `bassUnmaskDiagnostics()` accessor `:722`, `isRunning` for the "Waiting for Hi-Fi" state.
- JNI: `android/app/src/main/cpp/jni_bridge.cpp`, `nativeProcessorReadouts` (after `nativeBassUnmaskDiagnostics`).
- Settings fields in `AudioSettings`: `bassAttack`, `bassSustain`, `bassDimension`, `bassTube`, `analogTop`, `expression`,
  `experimentalBassUnmask`, `wholeMixFallback` (`model/EqModel.kt`).

## Latest owner priority — 5 October 2026, Extended EQ

After reporting success with 0.5.2 on the IM4, the owner says Extended EQ is not usable enough.
Make Svaresa its default/recommended controller with direct control of parametric/graphic EQ,
while preserving an explicit manual override. Avoid uncontrolled overlap throughout the combined
path; intentional adjacent-band overlap is necessary for smooth responses and must be checked
as a summed curve. Quality and listening preference remain the goal, not louder output.
Implementation and limits: [0.5.3 EQ workspace](EQ_WORKSPACE_0.5.3.md).

# Codex handoff — the Svan vision: sonic brilliance, unbreakable detection, a brain that earns its name

Fresh session? Read in this order: `AGENTS.md` → this file → `docs/HANDOFF.md` (0.5.2 section = newest truth) →
`docs/SMART.md` → `docs/CODEX_SVARAMANAS.md` (older research agenda, still valid) → `docs/AUDIOPHILE.md` (measured numbers)
→ `docs/PHONE_VALIDATION.md`. Repo `iamhariize-maker/Equalizer-app`. Work on the branch your session instructs
(currently `ccr-f859b567-dgrdoj`, PR #1 open). No other branches, no new PRs.

---

## 1. The owner's vision and philosophy (their intent, condensed — treat as the spec's soul)

**Svan** (Svanam Shreshtham: Ultimate Sound) is a global, root-free audiophile equalizer for Android that is meant to
feel like *the* sound layer of the phone: you play anything, anywhere, on any route, and it simply sounds the best it
can. The owner is a solo builder with **one main phone (TECNO LH7n, Android 14, HiOS) and no PC**; a second phone (LG V60)
exists. They judge by ear, hate unverified claims, and love Neutron-class depth, Wavelet/Poweramp-class reach and a
gold-on-charcoal identity. They weave 3-6-9 numerology into the brand (boot beats, "EQ exten9ed"); keep it.

Three pillars, in the owner's priority order:

1. **Supremely robust music-session detection — "if this isn't seamless, the rest won't matter."**
   Every player (YouTube Music, Apple Music, Spotify, Amazon, Neutron, Poweramp, HiBy, Onkyo, VLC, local files, games,
   video) on every route (speaker, wired, USB DAC, Bluetooth/LHDC/LE Audio) must be found within a beat, stay found
   across track changes, route changes, screen-off and app switching, and — when Android makes processing impossible —
   Svan must *say exactly why* instead of silently doing nothing. No root. Shizuku only as an optional one-time helper.
2. **Absolute sonic brilliance.** Measured, never imagined: 64-bit DSP, oversampled EQ, honest headroom, loudness-matched
   everything, low latency, no echo/doubling, no clipping, no "louder = better" tricks. Quality claims need numbers or
   blind-test evidence (AGENTS.md rule 2). Perceived resolution and clarity are the goal; respecting the music is the law.
3. **A sonic brain that earns its name — Svaramanas (svara + manas), with Svaresa as its supreme automatic mode.**
   Svaramanas = a *tonal police with an audiophile's heart*: senses (source, route, volume, time, listener), decides
   (bounded deterministic policy first; small ML only if it beats rules in blind tests), acts (existing DSP), verifies
   (loudness match, headroom, bypass, explanation). **Sound guide · Svaramanas** follows the listener's stated feel and
   instrument priorities (max 3–4, "whichever preserves quality better"). **Auto master · Svaresa** is the "magical auto
   switch": no fiddling, a **real day-and-night difference** versus off, yet never at the cost of honesty or fidelity.
   Floating gold स्व bubble + QS tile + notification are its faces; hold-to-compare is its conscience.

Non-negotiables: no root; Play Store target (policy-aware); works across OEM skins; no GPL code copied; honest reporting
of verified vs unverified; the owner never has to run commands.

**Owner's priority clarification (5 October 2026): streaming and popular apps first.**
Start with Spotify, Amazon Music, YouTube Music, then Apple Music and other widely
used streaming players (YouTube, Deezer, Tidal, SoundCloud). These offer fewer
advanced sound controls and are the main audience for Svan. Neutron, Poweramp,
HiBy, Onkyo and other advanced local/direct-output players come later because they
already have extensive native audio settings. Keep existing support, but do not
let their special output modes displace streaming detection work. Package visibility
and synthetic fixtures are not compatibility evidence.

---

## 2. State of the world (0.5.0 preview — read `docs/HANDOFF.md` for the full ledger)

**Detection stack (new in 0.5.0, all unit-tested + emulator-verified on API 34):**
`PlaybackSessions` (AudioService player list, keeps session-0 players) + `AudioFlingerDump` (threads, output devices, track
tables, effect chains incl. orphans, `Global session refs` = session→pid/uid/package) → `SessionLedger.merge` (either
source alone suffices; session-0 players resolved by pid) → `EffectVerifier` (PROCESSING / SUSPENDED / BYPASSED / WAITING
/ MISSING, settled-effects-only, bounded `MissingEffectRepair`) → `DetectionMonitor` (scan pass, public active-playback
cross-check, throttled server read, `DetectionStatus.assess` health verdict) → `SessionRouter` (per-source absence rules,
bypass-path sessions never muted) → UI health card, per-app rows, **Share diagnostic report** (`DiagnosticReport`).
Fault-injection (`test_blind_reports`, debug builds) proves detection survives either report being unreadable.

**Svaresa 2 (new in 0.5.0):** context layer on every engine — ISO 226 quiet-listening lift (`SvaresaBrain`, bass ≤ 6 dB,
treble ≤ 3 dB, speaker ≤ 1.5 dB), route classification, night comfort (clock auto/on/off: sub-bass −2.5 dB, 3.8 kHz
−1 dB, 1.5:1 multiband level-evening on system effects), AutoEq headphone recognition (`AutoHeadphone`, exact/unambiguous
only, never overrides the listener, removes itself on unplug/swap), plus a stronger native measured policy for Engine B
(`core/src/svaramanas.cpp`: wider limits, tilt shelves toward −2.5 dB/oct ±1.5, harshness smoothing).

**Verified:** core 65 tests (+sanitizers); JVM tests (AF parser, ledger, verifier, health, ISO 226 anchors, brain,
headphone matching, repair limits); CI green on `f9ec7a5` (run 37273679682): e2e 38 PASS/0 FAIL incl. detection with each
report blinded, real audio-server report parsed with the effect proven PROCESSING, Svaresa bass-vs-mid balance +5.6 dB
vs +5.4 predicted, night bounded; detection_release 10/10; compat API 29/30/33/35. App process *can* read
`media.audio_flinger` on stock API 34.

**NOT verified (the honest gap — this is where your first hours go):**
- Anything on the owner's TECNO: whether HiOS lets the app read `media.audio_flinger`, which players use direct/offload/
  MMAP paths, whether Neutron needs *Settings → Audio Hardware → DSP Effect (Device)* (third-party claim, unconfirmed),
  background survival with HiOS battery management ("Pause app activity if unused" can revoke permissions), real
  attach latency per track change.
- How any new curve *sounds*. Tilt target (−2.5 dB/oct ±1.5), volume→phon map (30–80 phon), night parameters and the
  reference level (65 phon) are first guesses; bounded and switchable.
- A ~0.5 s single-scan BLIND flash when a player starts (CI logs).
- Svaresa still has no ears on system effects (Visualizer is 8-bit and unsuitable; see Space B1).

**Evidence the owner already gave:** YT Music detected once then lost over Bluetooth; Apple Music seen once (Fosi IM4,
USB-C wired), later missing; Neutron never; Realme Buds Air 8 (LHDC on/off) no session; a screenshot of Hi-Fi with the capture
engine "waiting", −120 dBFS, "0 apps", YouTube Music listed only from a stale verdict. First ask of the owner after
installing 0.5.0: **Share diagnostic report** from Hi-Fi → Music detection, with the player ACTIVE, once per problem app
and route (Neutron with DSP Effect (Device) off and on).

---

## 3. Directed improvement spaces

Each space: **intent → what to build/research → how to prove it → done when**. Order = value to the owner's priorities.
Within a space, items are ranked. Anything touching the audio thread stays allocation-free and wait-free, with a numeric
test in `core/`. Coding quality matters here: prefer small, tested increments over sweeping rewrites.

### SPACE A — Unbreakable session detection (pillar 1; do this first)

**A0. Close the evidence loop (hours, not days).** Get the owner's diagnostic reports; add every real `dumpsys audio` /
`media.audio_flinger` excerpt as a **regression fixture** (`android/app/src/test/resources/`), fix parser/ledger gaps they
reveal (OEM column differences, MTK/HiOS quirks, package names absent on Android ≤12, uid filters). Update
`DetectionStatus.assess` wording to whatever the real failures are. Never guess a root cause the report can show.

**A1. Notification-access player recognition is rejected (owner decision, 5 October 2026).**
The owner requested its setting, service and permission be removed after the 0.5.1 APK
raised a Play Protect security/financial-fraud warning. It names players but does not
unlock audio sessions or improve sound. Do not reintroduce NotificationListenerService,
notification-access prompts, SMS permissions or accessibility access to recognize players.
Use the existing audio reports, playback callbacks and session broadcasts; measure
improvements in real detection/processing. Keep Play Protect enabled during testing.

**A2. Make "unprocessable" explicit and, where possible, fixable.** Per-app coach driven by `LedgerSession.pathLabel` +
`Verification`: direct / offload / bit-perfect / MMAP / exclusive-USB outputs bypass session effects *and* capture.
Research (cite sources) how Neutron, Apple Music (lossless), HiBy, Onkyo, Poweramp, UAPP actually output and which
setting makes them effect-capable; ship a JSON `AppProfile` table in assets with hedged, versioned advice; investigate
Android 14+ USB bit-perfect (`AudioMixerAttributes`) and whether Svan should *offer* a "processed" vs "bit-perfect" choice
per route. Verify with a synthetic AAudio/MMAP/OpenSL/offload tone source in `android/testsource/` so CI covers the paths.

**A3. Survive the OEM.** Foreground-service survival on HiOS/MIUI/ColorOS/OneUI: dirty-shutdown flag exists
(`SystemEqService.wasKilledByAndroid`); add per-OEM deep links (autostart, battery "unrestricted", lock in recents), handle
Android 14/15 FGS-type rules, `onTaskRemoved`, boot/package-replaced restart, permission auto-revoke detection ("Pause app
activity if unused"), and a visible "last heartbeat" in the health card. Prove with a soak harness (screen-off, doze,
force-stop recovery) on the emulator where possible and via a **Test Pilot** report on the phones.

**A4. Time-to-processed.** Measure and shrink the gap from "track starts" to "EQ audible": session churn per track
(ExoPlayer), attach cost (~1–2 ms per DynamicsProcessing band × 128), scan cadence (350 ms… 5 s ladder), the BLIND flash.
Ideas: pre-attach to a new session the moment the callback fires, push a coarse curve first then refine, keep effects across
gapless transitions, event-driven AF reads. KPI to add to e2e: ms from `AudioTrack.play` to PROCESSING; target < 1 s.

**A5. Test infrastructure for a hostile world.** Property/fuzz tests for `AudioFlingerDump` and `PlaybackSessions` over
mutated real fixtures (wrapped lines, localised text, extra columns, truncated dumps); multi-process apps, shared UID,
work-profile/secondary-user UIDs, apps with several sessions, sessions reused after restart, rapid route churn; emulator
scenarios for Bluetooth-like route changes (`cmd media_session`/audio policy fakes where possible). Keep `detection_release.sh`
at exactly 10 PASS lines unless you update the CI assertion deliberately.

**Done when:** on the owner's phone every installed player either shows *Processing — verified* within ~1 s of playing, or
a plain-language reason with the one action that fixes it; 30-minute screen-off run keeps detection alive; the diagnostic
report is no longer needed to know why.

### SPACE B — Svaramanas + Svaresa: the brain (pillar 3)

**B1. Give Svaresa ears on system effects — "Listen-only tap".** Design (research done, not built): an *analysis-only*
`AudioPlaybackCapture` `AudioRecord` (by UID, **no source muting, no replay**) feeding `SourceAnalyzer`, so Svaresa/Svaramanas
get loudness/tilt/mud/harsh/air on Engine A without Engine B's echo risk. Constraints: MediaProjection consent (Android 14
asks per session; offer once-per-boot guidance, never nag), FGS `mediaProjection`, capture-blocked apps (Spotify) stay
"static-smart", privacy (nothing stored/uploaded), CPU/battery (analyse ≤ 1 FFT/85 ms, pause when screen off), second-capture
refusal on some HALs (probe once, fall back). `Visualizer` was evaluated and rejected (8-bit data, ~42 dB range, post-effect).
Prove: analyser hears the same features as Engine B on the emulator test source; no audible change to the source.

**B2. Make "quiet listening" true to the ear.** Replace the assumed volume→phon map: per-route sensitivity learning
(BT absolute volume, wired, USB DAC), an optional in-app hearing/level calibration (ISO 226 / pink-noise matching, safe
levels), reference level choice. Keep caps; add a visible "assumes typical sensitivity" note until calibrated.

**B3. Headphone intelligence.** Ship an offline AutoEq index subset; improve name matching (brand aliases, TWS naming, BT
vs USB product names), remember per-device choice, and design an honest **generic fallback** (target-curve-only shelves by
form factor, derived from published measurements) for models not in AutoEq (e.g. Realme Buds Air 8, Fosi IM4 — check). Offer
FIR/convolution correction research (GraphicEQ vs parametric fit error vs CPU) for Engine B.

**B4. A real, lovable day-and-night difference — measured.** Define Svaresa's *intended audible deltas* per context
(quiet listening, night, speaker protection, headphone correction, measured mix repair) and a **loudness-matched blind A/B
tool** (Svaresa on/off, hidden, randomised, local results) to test them on the owner's phones. Tune by evidence. Add an
"adapting now" panel that shows real numbers (already partly there). Consider ambient-noise-aware intelligibility lift
(opt-in mic, on-device, never stored) as the *day* counterpart to night comfort.

**B5. Content-aware respect rules.** Speech vs music vs video (MediaSession type/metadata, spectral flatness/crest),
no tonal "fixing" of podcasts like music, respect lo-fi/heavy-bass genres, lossy-ceiling honesty, crushed-master caution
(already in core; extend and test). Never decide on genre guesses without a validated detector.

**B6. Dynamic EQ, resonance suppression, clarity.** From `docs/CODEX_SVARAMANAS.md` §4.1–2: masking-aware, reduction-only,
bounded, fast-bypass processors in `core/` with synthetic masker/target tests; Engine A equivalents limited to
DynamicsProcessing MBC (document its limits: thresholds are post-volume, 4 bands here). Opt-in track memory
(most-replayed segments) only via Space A1's media sessions, with privacy docs and a one-tap wipe.

**B7. Guided mode (Svaramanas) — make it as smart as Svaresa without taking its freedom.** Share context layers
(route/volume/night) as opt-in toggles, per-app and per-route saved profiles, instrument-priority overlap logic improved
(dynamic priority: lift guitars only while vocals aren't buried — needs B1 analysis), explanation lines that always match the
plan's real values.

**B8. Small ML controllers (last).** Only after B4's blind-test harness exists: vocal-presence/intent estimators (LiteRT
≲1M params) that *control DSP parameters*, trained on opt-in, level-matched preference data, validated on held-out tracks and
routes, and required to beat the rules. Do not claim a model that was not trained.

**Done when:** blind tests on the owner's phones prefer Svaresa-on at matched loudness for ≥ the agreed share of trials across
speaker/wired/BT, with no clipping and no loudness gain; every UI sentence is backed by a logged number.

### SPACE C — Absolute sonic brilliance in the signal path (pillar 2)

Rank by audible value and risk; every item needs a measured test and a phone check.
- **C1. Engine A truth:** system effects are gain-per-band at 10/40/80 ms windows; measure real resolution on the TECNO
  (`ResolutionProbe` exists), tune band count/window defaults per device, and tell the user the truth in the UI.
- **C2. Engine B latency/echo:** the 262 ms output queue and Bluetooth path; native-rate output (no AudioFlinger resample),
  AAudio/MMAP, A2DP/LE Audio buffer sizing, USB DAC path; fix the echo/doubling report or prove it was the capture replay.
- **C3. Headroom/loudness model:** lesson from 0.5.0 — auto headroom pre-attenuates by the largest boost, so boosts never
  raise absolute level. Document it in the UI where users compare; consider true-peak/inter-sample safe limiting and an
  optional loudness-normalised comparison mode.
- **C4. Core DSP:** linear-phase/min-phase options, crossfeed, convolution, noise-shaped dither verification, CPU cost of
  4×/8× oversampling on the phones (`eqcore_bench` on-device).
- **C5. Measurement lab in-app:** one-tap self-test that plays tones through the real path and reports level/THD/latency
  (builds on e2e `measure_mix`), so every claim on screen has a number behind it.

### SPACE D — Trust, safety, release
Play policy text (SYSTEM_ALERT_WINDOW, MediaProjection, FGS, DUMP via Shizuku/ADB), `docs/PRIVACY.md` updates for any new
permission, release signing inputs (owner-supplied only), licence decision (ask), battery cost report, accessibility
(content descriptions, large fonts — the share button clipping at 320 dp shows small screens bite).

---

## 4. Method and guardrails (learned the hard way — do not relearn)

1. **Evidence first.** Real device reports beat theory. When CI contradicts a check, find out whether the *check* or the
   *DSP* is wrong (0.5.0's headroom lesson) before touching either. Never loosen, skip or delete a check to get green.
2. **Never win by being louder; never claim "sounds better" without blind A/B at matched loudness.** Mark guesses as guesses
   in code, UI and docs.
3. **Tests first for DSP** (`core/tests/test_main.cpp`, expected-vs-measured dB). JVM tests for pure logic. The emulator
   e2e (`android/scripts/e2e.sh`, `detection_release.sh`) is the only real device we have in CI: extend it with every new
   behaviour, and **look at the screenshots** (`screens/*.png`) — layout bugs only show there. `detection.txt` must keep
   exactly 10 PASS lines (asserted in `.github/workflows/ci.yml`) unless you change both deliberately.
4. **One DynamicsProcessing per session; a session belongs to exactly one engine** (`SessionRouter`). Never attach both.
5. **Detection redundancy is sacred:** no new feature may make detection depend on a single source again. A failed or
   skipped report is "unknown", never "absent" (see `SessionRouter.sync` absence rules).
6. **Design:** one gold hue on warm charcoal; Ember only for warnings; serif titles; plain-language first, Sanskrit names as
   identity. Small phones (320 dp) must not clip.
7. **Licences:** AutoEq MIT, icon glyphs OFL, Shizuku API MIT (notice shipped). RootlessJamesDSP/JamesDSP are GPL —
   reading for ideas only. App licence undecided: ask.
8. **Local build:** Android SDK + NDK 27.0.12077973 + CMake 3.22.1, JDK 17 (JDK 21 worked locally). Maven Central may return
   HTTP 429 through shared proxies: retry. `core`: `cmake -S core -B build/core && cmake --build build/core -j &&
   ./build/core/eqcore_tests` (65 tests). Android: `cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug
   testDebugUnitTest`. Emulator tests run only in GitHub Actions.
9. **Git:** `git -c user.name=iamhariize-maker -c user.email=iamhariize@gmail.com`; commit trailers as in `git log`;
   push only to the session's branch; each push runs CI twice while PR #1 is open.
10. **Roles that worked:** research and preparation first, then small implemented increments with root-cause fixes; keep a
    running verified/unverified ledger in `docs/HANDOFF.md` and update it with what CI *actually showed*.

---

## 5. Suggested first sequence

1. Check CI on the latest commit; read `docs/HANDOFF.md` 0.5.0. (Green on `f9ec7a5` / `7fe5133` docs-only.)
2. Ask the owner for diagnostic reports (A0), starting with Spotify, Amazon Music and YouTube Music;
   until they arrive do A5 (fixtures/fuzz) and streaming-style media-session scenarios. Never label synthetic inputs as phone reports.
3. A3 + A4 (survival and time-to-processed), then A1 (media sessions).
4. B1 listen-only tap, then B4 blind A/B tool — they unlock honest tuning of everything else.
5. B2/B3 personalisation, C1/C2 on-device measurements, B5–B7, then B8 only with evidence.
6. Keep `docs/SMART.md`, `docs/PHONE_VALIDATION.md`, `docs/PRIVACY.md` and `docs/HANDOFF.md` truthful at every step; deliver
   the CI `Svan-preview` APK with screenshots and a plain verified/unverified list.

## 6. Questions that need the owner (batch them; they have no PC)
- Diagnostic reports first from Spotify, Amazon Music and YouTube Music (then Apple Music), on speaker/wired/BT.
  Neutron's DSP Effect (Device) off/on tests come later.
- Which headphone models to prioritise for AutoEq coverage; app licence choice; Play listing intentions.
- How much consent friction is acceptable for the listen-only tap (Android 14 asks each session).
- Whether PR #1 is intended (opened 05:52 UTC on 2026-10-05, not by the coding agents; each push now runs CI twice).

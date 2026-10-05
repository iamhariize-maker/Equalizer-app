# Codex kickoff — Svaramanas (Svan's sound intelligence)

Fresh session? Read in this order: `AGENTS.md` → this file → `docs/SMART.md` (design + status) →
`docs/HANDOFF.md` (older state, gotchas) → `docs/AUDIOPHILE.md` (measured numbers).
Repo `iamhariize-maker/Equalizer-app`, branch **`ccr-208702a3-2mju42`** (no PR, don't touch other branches).
Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` + `Claude-Session:` line as in `git log`;
identity `git -c user.name=iamhariize-maker -c user.email=iamhariize@gmail.com`.

## 1. What the owner wants (their words, condensed)

Svan = a **global audiophile equalizer for Android** (no root, ever — "that will kill the app's
adaptation"), going to the **Play Store**. Inside it lives **Svaramanas** (svara = sound, manas = mind):

- A **floating logo** (स्व mark + three "manas" nodes) on every screen/over other apps. Tap → dialog
  *"What kind of sound do you want?"* → feel (warm/bright/balanced/punchy/spacious/intimate) →
  *"Instruments you prioritise?"* — categories, **max 3–4, "whichever preserves audio quality better"**.
- **Persona / priority:** a *tonal police* that monitors and manufactures ways to get the best output for
  the user **or as per the user's priority**; in his heart a **passionate audiophile with a knack for
  clarity / perceived resolution, who respects beautiful sound however new it sounds.**
- **Silently track favourite tracks and the most-replayed part of a song** and work its magic there.
- Work like **JBL "AI clarity"-style** processing ("don't steal their code, implement my vision") for
  **any source**: YT Music, Spotify, Apple Music, Amazon Music, Neutron, HiBy, Onkyo, local/streaming.
- Must work across Android skins (Oxygen/Funtouch/ColorOS/MIUI/HiOS…); the owner's test devices:
  **TECNO LH7n (Android 14, main phone, YT Music, Fosi Audio IM4 + Realme Buds Air 8)** and an
  **LG V60 ThinQ** (YT Music, Neutron, Apple Music subscriptions; Spotify free can be added).
  The owner has **no PC**; everything must be verified in GitHub Actions or on those phones.
- Brand/design: gold-on-charcoal (see AGENTS.md rules 4–5); full name "Svanam Shreshtham: Ultimate Sound".

## 2. What exists now (0.4.0-svaramanas-preview, commit `4a29242`)

**Core (`core/`, C++17, 62 tests, ASan/UBSan/TSan in CI):**
- `analyzer.h/.cpp` `SourceAnalyzer` — gated K-weighted loudness (EBU 3341 sine → −23.0 LUFS), peak, PLR,
  clipping, stereo correlation/mono, lossy ceiling, third-octave balance, mud/boom/harsh/air deviations
  from the mix's own tilt. Runs inside `Engine::process` on the *input* (Engine B only).
- `svaramanas.h/.cpp` — deterministic policy: feel × categories (first 3 always win; 4th only if no range
  clash; never 5), 6 dB emphasis budget, ±3 dB per band, overlap softening, analyser trims (≤2.5 dB),
  lossy-ceiling / mono / crushed-master respect rules, fixed band skeleton, reason codes, **loudness
  match** (predicted K-weighted delta → preamp trim). All 6876 combos pass guardrails; measured
  loudness delta through the real engine ≤0.1 dB.
**App (`android/app/.../svaramanas/` + `ui/SvaramanasPanel.kt`, `ui/SvaraMark.kt`):**
`Svaramanas` controller (request persisted; plan recomputed every 3 s on Engine B with 0.5 dB slew,
static on Engine A), `SvaramanasActivity` (translucent dialog), `SvaramanasBubbleService` (overlay
bubble: tap/hold-to-compare/drag), `SvaramanasTileService` (Quick Settings), notification action,
in-app bubble. Smart layer lives in `EqState.smart` (+`smartBypass`) so **both engines** run it.
**Also this session:** Engine B **silence watchdog** (`CaptureService`/`SessionRouter.onCaptureSilent`) and
idle-DSP skip; fixed preview signing key (`android/preview.keystore`, public, NOT the Play key);
CI `compat` matrix (API 29/30/33/35 install+smoke) and APK sanity step.

## 3. Verification status — be honest about this

- **CI on `4a29242` (run 37241554783) and on `9dac168` (run 37239505853) was NOT finished** when this was
  written. `core`, `android` (build/lint/unit) and all 4 `compat` jobs were green; **`emulator-e2e`
  was still running (run 37239505853 had been going 55 min vs ~10 normally → investigate a hang
  in e2e T20 first)**. New unverified checks: **T20** (capture silent mid-playback fails open), **T21**
  (Svaramanas plan reaches system effects at the predicted level), **T22** (analyser hears captured
  source), and screenshots `6-svaramanas*.png` (dialog, 3-4 rule clash message). Read the CI logs
  (`mcp__github__get_job_logs` / `gh run view`), look at the screenshots, fix root causes — never
  loosen or skip checks.
- **Nothing in Svaramanas has been listened to by a human.** Thresholds in `analyzer.cpp`/`svaramanas.cpp`
  are first guesses (marked below). No claim of "sounds better" may ship until blind A/B evidence exists.
- Real-phone log from the owner (TECNO, YT Music over BT): `capture level: peak=0.0000` for minutes with
  DSP 10–40% busy and output queue fixed at **262.6 ms**, underruns=1. Cause not established (paused vs
  muted-but-silent). The watchdog + idle skip address it; the 262 ms Bluetooth-era latency is **unfixed**.
- Owner's earlier APK didn't install on the LG V60; most likely a signature mismatch from random CI debug
  keys (fixed by the fixed preview key) or a version downgrade. **Exact installer message still unknown.**
  Android 10–15 emulators install fine; OEM skins can't be emulated.

## 4. Research agenda (this is what the owner wants Codex to do)

Do real, cited research (papers, AES/ITU docs, open implementations **for reference only — GPL code must
not be copied**, e.g. RootlessJamesDSP; MIT/BSD is fine with attribution), then turn findings into
tested code. Prioritise by user-audible value, then by risk.

1. **Masking-aware dynamic EQ** (Bark/ERB masking thresholds, spectral-flatness/crest-based triggers,
   attack/release design) and **automatic resonance suppression** (à la Soothe-type processors, from first
   principles). Needs a measured test on synthetic masker/target pairs.
2. **Perceived clarity / resolution metrics**: which objective measures (specific loudness, sharpness,
   roughness, spectral centroid, ITU-R BS.1387 / PEAQ-style features, STOI/ESTOI for speech/vocal) can
   we compute on-device and validate against listening? Propose a metric to optimise and a protocol.
3. **Personalisation**: equal-loudness / hearing-test EQ (ISO 226, Samsung "Adapt Sound"-style flow, safe
   levels), volume-aware loudness compensation, **headphone auto-ID** (Bluetooth/USB name → AutoEq
   entry; e.g. Fosi IM4, Realme Buds Air 8), microphone noise-aware intelligibility lift (speech
   intelligibility index).
4. **Lossy-source handling**: honest bandwidth-extension / de-ringing options (Sony DSEE-style claims are
   unverifiable — what is actually evidenced?), codec fingerprints (AAC/Opus/MP3 ceilings), and what
   YT Music / Spotify / Apple / Amazon actually deliver (bitrate, loudness normalisation, DRM
   capture flags).
5. **Source/app profiles + "player coach"** (`AppProfile` JSON shipped in the APK): per-app capture
   allowed?, normalisation default, own EQ, exclusive/bit-perfect USB mode (Neutron/HiBy/Onkyo bypass the
   mixer — Svan can't touch them). Research real behaviour; probe results come from the owner's V60
   via a **Test Pilot** report (to be built).
6. **Track memory** (opt-in): the owner rejected notification-listener access on 5 October 2026.
   Do not restore it for metadata or replay inference; defer this feature until an approved
   source of evidence exists. See the current restriction in `docs/SMART.md`.
7. **OEM skins / Play policy**: battery-optimisation and autostart handling per OEM (dontkillmyapp-style
   data), "Pause app activity if unused" (the owner's TECNO has it ON for Svan — it can revoke the mic
   permission), `SYSTEM_ALERT_WINDOW` and MediaProjection/foreground-service justification text,
   Android 14/15 foreground-service-type rules.
8. **Latency/battery**: the 262 ms output buffer and Bluetooth path; AAudio/MMAP, buffer sizing for
   A2DP/LE Audio; native-rate output (avoid AudioFlinger resampling), USB DAC bit-perfect path
   (Android 14+ `USB bit-perfect mixer attributes`), power cost of 4x/8x oversampling on the real phones.
9. **Small on-device ML controllers** (LiteRT, ≲1M params) only after rules are validated: vocal-presence
   estimator, intent/genre classifier ("respect the music"). ML only *controls* DSP parameters.

## 5. Next build increments (suggested order)

1. Get CI green on `4a29242`; fix the e2e hang/T20–T22 and screenshots; deliver the tested APK.
2. **Test Pilot** (one-tap per-player probe report: capture allowed, engine, double-audio, level change,
   route) + `AppProfile` table + player coach UI. Run on the V60 and TECNO.
3. Overlay bubble polish: hide over full-screen video, edge snapping, permission rationale screen, fallback
   when overlay permission is denied.
4. Dynamic EQ / resonance suppression / noise-aware lift / loudness compensation (research §4.1–3).
5. Hearing test + headphone auto-ID. 6. Track memory. 7. In-app **blind A/B tool** (loudness-matched,
   randomised, results local) — the only evidence for any quality claim.
8. OEM helper screen (detect manufacturer, deep-link to the right battery/autostart/overlay settings).

## 6. Rules and traps (learned the hard way)

- **Never win by being louder.** Every smart change is loudness-matched (test it with the engine, not the
  predictor). Keep `Guardrails` constants (`kMaxBandDb`, `kEmphasisBudgetDb`) and add tests for new knobs.
- **Never claim sound quality you haven't measured/heard** (AGENTS.md rule 2). Mark guesses as guesses.
- Engine A (system effects / DynamicsProcessing) **cannot see audio** — Svaramanas there is static-smart
  (route, hearing, loudness, noise, taste). Adaptive analysis exists only on Engine B and only for apps that
  allow capture (Spotify etc. are blocked and stay on Engine A).
- One DynamicsProcessing per session: a session belongs to exactly one engine (`SessionRouter`).
- Test device reality: only GitHub Actions emulators (KVM). Cached-app freezer kills non-foreground players
  (the test player is a foreground media service for that reason). `adb install -r` keeps data; `pkill -f`
  matches itself; `-Pandroid.injected.build.abi` makes a testOnly APK (don't).
- Audio thread: allocation-free, wait-free. Keep new DSP in `core/` with a numeric test.
- Licences: app licence undecided (ask the owner); AutoEq MIT; icon glyphs SIL OFL; no GPL copying.
- No root, no Shizuku dependency on the default path (Shizuku stays an optional extra).

## 7. Known first-guess numbers to validate (listen + measure)

`analyzer.cpp`: lossy ceiling = highest 8-bin stretch within 60 dB of the 1–6 kHz reference; deviation
thresholds boom 3 dB / mud 2 dB / harsh 2 dB, correction slope 0.6; air lift when air < −4 dB on ≥17.5 kHz
sources; crushed master = PLR < 8 dB or > 1 clip/s; `svaramanas.cpp` per-category band shapes and the
`Feel` presets (all hand-made). Update cadence 3 s, slew 0.5 dB.

## 8. How to verify (before every push)

```sh
cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests        # 62 tests
cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest \
  :testsource:assembleCapturableDebug :testsource:assembleBlockedDebug
```
Then push and read the `emulator-e2e` log + artifacts (`e2e-results`: `e2e_results.txt`,
`detection/`, `screens/*.png` — **look at the screenshots**), `Svan-preview` (release APK; verify
no testOnly, fixed preview cert `CN=Svan Preview`, v2 signature). CI also runs `compat` on API 29/30/33/35.

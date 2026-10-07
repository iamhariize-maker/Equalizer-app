# AGENTS.md — Svan (Svanam Shreshtham: Ultimate Sound)

Read this first, then `docs/CODEX_VISION.md` (newest brief: vision, honest state, directed improvement spaces), `docs/HANDOFF.md` (full state, open items, gotchas) and `docs/CODEX_SVARAMANAS.md` (older research agenda).

## What this is
**Svan** is a system-wide audiophile equalizer for **Android** (iOS cannot do global EQ; out of scope).
Two audio engines share one preset/state:

Current product priority: **streaming and popular apps first** (Spotify, Amazon Music, YouTube Music,
then Apple Music and other mainstream players). Neutron/Poweramp/HiBy/Onkyo's advanced output modes
come later. Do not claim commercial-player or TECNO compatibility from synthetic tests.
Owner's additional device priority: LG V60 on Android 13. Run the full routing/control/production
suite on API 33 as well as API 34; emulators do not verify LG's Quad DAC or background policies.

- **Engine A — system effects.** `DynamicsProcessing` attached to other apps' audio sessions
  (Wavelet-style). Gain-per-band only; low latency; works on capture-blocked apps (Spotify).
- **Engine B — audiophile engine.** `AudioPlaybackCapture` → native 64-bit C++ chain → `AudioTrack`.
  The source app is muted by a top-priority `DynamicsProcessing` at -200 dB input gain
  (capture taps audio *before* session effects). Full DSP: oversampled parametric EQ, bass shaper,
  mid/side vocal tuner + orchestral amplifier, dither, gain protection.

`SessionRouter` gives every audio session to exactly ONE engine (they share one `DynamicsProcessing`
instance per session; never attach both).

Owner follow-up (6 October 2026): implement OEM-safe detection fallbacks. Normal setup
reads fixed audio reports via a Shizuku shell UserService instead of granting app DUMP.
Owner decision (7 October 2026): payment apps refuse to run beside Shizuku, so a user-tapped
"Keep enhanced detection without Shizuku" button (`DumpGrant`) grants Svan its own DUMP once
through Shizuku. Never grant it without that tap, and never grant anything else.
Keep the independent session-broadcast path, existing/manual grants, Play Protect, and all
DSP/routing quality safeguards.

Owner decision (7 October 2026, final): no notification listener, SMS or accessibility capability,
for any purpose. Play Protect's enhanced fraud protection flagged the sideloaded 0.5.6 build as a
financial-fraud risk because it declared a NotificationListenerService (optional player recognition).
That feature is removed and `check_manifest_permissions.py` rejects any such declaration.

## Repo map
| Path | What |
|---|---|
| `core/` | Portable C++17 DSP library `eqcore` (CMake). 98 unit tests in `core/tests/test_main.cpp` (no framework). |
| `android/app/src/main/cpp/` | JNI bridge → `eqcore` |
| `android/app/src/main/java/app/svan/` | Kotlin: engines, routing, repository, UI (Compose); `svaramanas/` = controller, dialog, bubble, QS tile |
| `android/app/src/main/java/app/svan/ui/` | Screens (Sound, EQ, Presets, Hi-Fi, Lab), `Theme.kt` (gold palette), `Brand.kt` (boot animation, brand line, "EQ exten9ed"), `Knob.kt`, `ResponseGraph.kt` |
| `android/testsource/` | Fake music app (flavors `capturable` / `blocked`) used by emulator tests |
| `android/scripts/` | `e2e.sh` (39 PASS/FAIL checks), `detection_release.sh` (10 setup/output checks), `diag.sh`, `smoke_release.sh`, `screens.sh` |
| `tools/brand/` | Generates the launcher icon (स्व) from OFL font outlines with HarfBuzz |
| `.github/workflows/ci.yml` | **The real test device**: core tests (+ASan/UBSan/TSan), Android build/lint/unit tests, KVM emulator e2e |
| `docs/` | `HANDOFF.md`, `ARCHITECTURE.md`, `AUDIOPHILE.md` (measured numbers), `SPIKE.md`, `SMART.md` (Svaramanas: design, status, next increments) |

## How to verify (do this before every push)
```sh
# Core (fast, deterministic)
cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests
# Android (needs Android SDK 36, NDK 27.0.12077973, CMake 3.22.1, JDK 17)
cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest
```
The emulator tests only run in **GitHub Actions** (`emulator-e2e` job, KVM). Push, then read the job
log: it prints `PASS`/`FAIL` lines; the job fails on any `FAIL` or if `PASS release smoke test` is
missing. Artifacts `e2e-results-api33` / `e2e-results-api34` have the log and screenshots (`screens/*.png`, boot frames). **Look at the
screenshots** — layout bugs only show there.

## Rules that matter
1. **Don't push to `main`/other branches.** Work on the branch your session instructs (currently `claude/codex-audio-crackling-amplifier-gkj007`, which contains all of `ccr-f859b567-dgrdoj`). No new PR unless the user asks.
2. **Never claim sound quality you haven't measured.** Every number in the UI/docs comes from a test. The owner reports successful listening on TECNO/IM4 with 0.5.2; this does not establish a complete device/player matrix.
3. **Tests first for DSP.** New processors need a measured test (see existing ones: expected vs measured dB). Keep the audio thread allocation-free and wait-free (see `ParametricEq`, `StereoTuner`).
4. **Design:** one gold hue on warm charcoal (`Theme.kt` tokens: Gold/Molten/Bronze; Ember ONLY for warnings; Ash for "negative" sides). Serif titles. Polished, not colourful. Don't introduce new hues.
5. **Brand:** app name "Svan"; full name "Svanam Shreshtham: Ultimate Sound"; EQ screen title is "EQ exten9ed" (the first "d" is a vertically flipped d = a 9; screen readers say "EQ Extended"). Every screen carries the SVANAM SHRESHTHAM brand line.
6. **Licences:** do NOT copy GPL code (RootlessJamesDSP/JamesDSP are GPL — read-only reference only). AutoEq data/targets are MIT; icon glyphs are SIL OFL (attribution in README). Owner decision (6 October 2026): original Svan code is **All rights reserved**; retain third-party notices. See LICENSE.
7. **Git:** commit messages end with the Co-Authored-By/Claude-Session lines used in `git log`. Use `git -c user.name=iamhariize-maker -c user.email=iamhariize@gmail.com`.

Owner decision (5 October 2026, EQ follow-up): Svaresa controls Extended EQ by default and is
recommended. Preserve the separate manual curve/layout, expose the real applied automatic bands,
and check the combined response instead of independently stacking boosts. Adjacent filters may
intentionally overlap; never claim overlap-free processing or guaranteed listener preference.
See `docs/EQ_WORKSPACE_0.5.3.md`.
Owner's controls follow-up: fine mechanical steps and smooth relative gestures, no vibration.
See `docs/PRECISION_CONTROLS.md`; preserve centre scrolling while allowing rim rotation.

Owner's engine follow-up: give Svaresa broader authority only where it improves measured quality.
0.5.4 adds engine protection authority and smooth native EQ/gain transitions, not forced quality-mode
changes or additional taste effects. See `docs/ENGINE_QUALITY_0.5.4.md`.

## Top open items (details in docs/HANDOFF.md)
1. Verify on the next CI screenshots: boot name fits on one line with margins; the flipped **d** reads as a 9 (not "q"); the स्व icon on the Android 12+ splash.
2. **Real-phone validation** (TECNO LH7n/Android 14 and LG V60/Android 13, no PC): the owner reports earlier listening success; new DSP, player/device coverage and LG Quad DAC behavior still need phone checks.
3. Product gaps: per-app engine UI, foreground-service robustness, release signing, Play Store policy (MediaProjection/foreground service).

0.5.5 adds reconstructed-peak protection, selective dynamic EQ, bounded headphone calibration
and blind matched listening. See docs/QUALITY_LAB_0.5.5.md. Four release quality checks are required;
keep the ten detection, 39 routing, nine workspace and eight control checks intact.
Production signing uses a private owner key outside Git/CI, never preview.keystore. Four additional
production-mode checks use a disposable CI key. See docs/RELEASE_SIGNING.md; preserve settings
export/restore and the production Activity's rejection of scripted command extras.

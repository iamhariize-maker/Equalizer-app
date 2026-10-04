# AGENTS.md — Svan (Svanam Shreshtham: Ultimate Sound)

Read this first, then `docs/HANDOFF.md` (full state, open items, gotchas).

## What this is
**Svan** is a system-wide audiophile equalizer for **Android** (iOS cannot do global EQ; out of scope).
Two audio engines share one preset/state:

- **Engine A — system effects.** `DynamicsProcessing` attached to other apps' audio sessions
  (Wavelet-style). Gain-per-band only; low latency; works on capture-blocked apps (Spotify).
- **Engine B — audiophile engine.** `AudioPlaybackCapture` → native 64-bit C++ chain → `AudioTrack`.
  The source app is muted by a top-priority `DynamicsProcessing` at -200 dB input gain
  (capture taps audio *before* session effects). Full DSP: oversampled parametric EQ, bass shaper,
  mid/side vocal tuner + orchestral amplifier, dither, gain protection.

`SessionRouter` gives every audio session to exactly ONE engine (they share one `DynamicsProcessing`
instance per session; never attach both).

## Repo map
| Path | What |
|---|---|
| `core/` | Portable C++17 DSP library `eqcore` (CMake). 40 unit tests in `core/tests/test_main.cpp` (no framework). |
| `android/app/src/main/cpp/` | JNI bridge → `eqcore` |
| `android/app/src/main/java/app/svan/` | Kotlin: engines, routing, repository, UI (Compose) |
| `android/app/src/main/java/app/svan/ui/` | Screens (Sound, EQ, Presets, Hi-Fi, Lab), `Theme.kt` (gold palette), `Brand.kt` (boot animation, brand line, "EQ exten9ed"), `Knob.kt`, `ResponseGraph.kt` |
| `android/testsource/` | Fake music app (flavors `capturable` / `blocked`) used by emulator tests |
| `android/scripts/` | `e2e.sh` (12 PASS/FAIL checks), `diag.sh`, `smoke_release.sh`, `screens.sh` |
| `tools/brand/` | Generates the launcher icon (स्व) from OFL font outlines with HarfBuzz |
| `.github/workflows/ci.yml` | **The real test device**: core tests (+ASan/UBSan/TSan), Android build/lint/unit tests, KVM emulator e2e |
| `docs/` | `HANDOFF.md`, `ARCHITECTURE.md`, `AUDIOPHILE.md` (measured numbers), `SPIKE.md` |

## How to verify (do this before every push)
```sh
# Core (fast, deterministic)
cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests
# Android (needs Android SDK 35, NDK 27.0.12077973, CMake 3.22.1, JDK 17)
cd android && ./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest
```
The emulator tests only run in **GitHub Actions** (`emulator-e2e` job, KVM). Push, then read the job
log: it prints `PASS`/`FAIL` lines; the job fails on any `FAIL` or if `PASS release smoke test` is
missing. Artifact `e2e-results` has the log and screenshots (`screens/*.png`, boot frames). **Look at the
screenshots** — layout bugs only show there.

## Rules that matter
1. **Don't push to `main`/other branches.** Work on `ccr-208702a3-2mju42` (current branch) unless told otherwise. No PR unless the user asks.
2. **Never claim sound quality you haven't measured.** Every number in the UI/docs comes from a test. Real-device listening has NOT happened yet.
3. **Tests first for DSP.** New processors need a measured test (see existing ones: expected vs measured dB). Keep the audio thread allocation-free and wait-free (see `ParametricEq`, `StereoTuner`).
4. **Design:** one gold hue on warm charcoal (`Theme.kt` tokens: Gold/Molten/Bronze; Ember ONLY for warnings; Ash for "negative" sides). Serif titles. Polished, not colourful. Don't introduce new hues.
5. **Brand:** app name "Svan"; full name "Svanam Shreshtham: Ultimate Sound"; EQ screen title is "EQ exten9ed" (the first "d" is a vertically flipped d = a 9; screen readers say "EQ Extended"). Every screen carries the SVANAM SHRESHTHAM brand line.
6. **Licences:** do NOT copy GPL code (RootlessJamesDSP/JamesDSP are GPL — read-only reference only). AutoEq data/targets are MIT; icon glyphs are SIL OFL (attribution in README). App licence is **not chosen yet** — ask the user.
7. **Git:** commit messages end with the Co-Authored-By/Claude-Session lines used in `git log`. Use `git -c user.name=iamhariize-maker -c user.email=iamhariize@gmail.com`.

## Top open items (details in docs/HANDOFF.md)
1. Verify on the next CI screenshots: boot name fits on one line with margins; the flipped **d** reads as a 9 (not "q"); the स्व icon on the Android 12+ splash.
2. **Real-phone validation** (the user has one phone, a TECNO LH7n, Android 14, no PC): everything so far is emulator-verified only.
3. Product gaps: per-app engine UI, foreground-service robustness, release signing, Play Store policy (MediaProjection/foreground service), app licence.

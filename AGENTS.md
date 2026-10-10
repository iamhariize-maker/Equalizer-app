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
  (Wavelet-style). Gain-per-band only; low latency; the fallback when an installed app blocks capture.
  Do not infer Spotify's installed capture policy from a package name or silent reads.
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

Owner decision (11 October 2026): remove the audio recording feature entirely (Recording mode, proof export, the
eight-second clip tap, and any saving of captured audio), because the app is published from a website and piracy
allegations are a risk. The whole-phone output fallback is off by default and has no visible name. Loudness processing
keeps the listener's level: the trim is rate-limited and never steps, and the output may be up to 0.1 dB louder but never
quieter. The Lab tab stays and gains working tools, such as Bass detail and Highs experimental; selective dynamic EQ is opt-in. Full work order:
`docs/CODEX_HANDOFF_2026-10-11.md`.

## Repo map
| Path | What |
|---|---|
| `core/` | Portable C++17 DSP library `eqcore` (CMake). 164 unit tests in `core/tests/test_main.cpp` (no framework). |
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
1. **Don't push to `main`/other branches.** Work on the branch your session instructs. Branch names in older briefs and docs are history, not instructions. No new PR unless the user asks.
2. **Never claim sound quality you haven't measured.** Every number in the UI/docs comes from a test. The owner reports successful listening on TECNO/IM4 with 0.5.2; this does not establish a complete device/player matrix.
3. **Tests first for DSP.** New processors need a measured test (see existing ones: expected vs measured dB). Keep the audio thread allocation-free and wait-free (see `ParametricEq`, `StereoTuner`).
4. **Design:** gold leads on warm charcoal (`Theme.kt` tokens: Gold/Molten/Bronze; Ember ONLY for warnings; Ash for "negative" sides). Serif titles. Polished, not colourful.
   Owner decision (8 October 2026): add elegance with jewel accents and drawn illustrations. Each accent has one meaning and
   is used only for it: Lapis/Indigo = air, treble, sky, listening; Peacock = space and width; Lotus = the voice; Tulsi = your
   learned sound and confirmations. Illustrations are drawn in code (`ui/Illustrations.kt`: the EQ landscape, the lotus mandala,
   the sound-style glyphs), sit behind content as decoration and never change a layout. Don't add hues outside this set.
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
Latest (10 Oct 2026): 0.5.13/code 20 built from `docs/BUILD_BRIEF_0.5.14.md` (status table at its top): DUMP-free return path for
parked players (`ParkPolicy`), analyser-driven shrill guard with voice protection and a 3-6 kHz budget, `space` as a plain side
EQ, stronger bass texture plus off-by-default Bass detail and Analogue top switches. Nothing in it has been heard yet.
Latest (9 Oct 2026): capture recovery 0.5.10/code 17, see `docs/CAPTURE_RECOVERY_0.5.10.md`.
A single playback-recorder lease serializes main/probe/diagnostic records; the idle main record closes.
Legacy silence-based blocks are migrated; a negative sample never becomes a persistent app-policy verdict.
Installed manifest and UID-wide audio policy provide direct opt-out evidence. Preserve before/after-mute
proof, fail-open and one-engine ownership. Commercial-player capture permission remains unverified.
Full API 34 run 37942817135 passes all 14 recovery, 8 production, 41 routing and existing gates.
Source is frozen at 3dabee6; the exact production payload is owner-signed with all 86 entries unchanged.
API 33 remains unqualified: hosted VM exits and software-CPU system-wide ANRs/service deadline
failure prevented completion. Preserve failed evidence; do not describe this update as API 33 qualified.
Optional WAV export opens no separate AudioRecord/MediaProjection and remains after the owner's review.
Latest 0.5.8 quality work responds to LG owner feedback: EQ/tuner bypass improves reported fog,
Amazon remains hidden and its own EQ does not open Svan. Read `docs/QUALITY_RECOVERY_0.5.8.md`.
Local native (164), ASan/UBSan (164), TSan publication (5), Android JVM (230), debug/release
build and lint checks pass. CI 37745825272 at `35c9bd7` passes all eleven jobs; API 33/34 logs,
recording exports and screenshots were reviewed. The exact tested production payload is verified
and signed with the original owner key; see `docs/QUALITY_RECOVERY_0.5.8.md` for provenance.
Amazon native processing and new physical-phone qualification remain unresolved.
0. Combined 0.5.7/code 14 preserves the exact owner spatial-test source/features plus recording/detection. CI 37702568531 passes all eleven jobs; full API 33/34 logs/images were reviewed and the tested production APK was delivered signed with the original owner key. See `docs/COMBINED_APK_VERIFICATION_0.5.7.md`. Twelve DUMP-free checks supplement every existing PASS set. Amazon-first and Apple physical-phone qualification remain open; shared-output EQ is experimental with no commercial-player/route guarantee.
1. Recording (`bbbb790`) was removed on 11 October 2026 at the owner's direction; its CI evidence stays in git history. Keep the other required PASS sets intact.
2. **Real-phone validation** (TECNO LH7n/Android 14 and LG V60/Android 13, no PC): the owner reports earlier listening success; new DSP, player/device coverage and LG Quad DAC behavior still need phone checks.
4. Product gaps: per-app engine UI, foreground-service robustness and Play Store policy (MediaProjection/foreground service). Original-key private APK delivery is verified; Play distribution/phone qualification remain separate work.

0.5.5 adds reconstructed-peak protection, selective dynamic EQ, bounded headphone calibration
and matched-level release checks. See docs/QUALITY_LAB_0.5.5.md. Four release quality checks are required;
keep the ten detection, 39 routing, nine workspace and eight control checks intact.
Production signing uses a private owner key outside Git/CI, never preview.keystore. Four additional
production-mode checks use a disposable CI key. See docs/RELEASE_SIGNING.md; preserve settings
export/restore and the production Activity's rejection of scripted command extras.

Recording mode (Hi-Fi) was removed on 11 October 2026 at the owner's direction (see the decision above). Its docs were deleted.

Grounded sound (8 Oct 2026, built on the 0.5.8 base): Svaresa carries the owner's house voicing for deep, clean bass
(Rahman / Massive Attack) and natural transients and atmosphere (Wilco): a 65 Hz *foundation* that lifts only what a
track lacks (none with measured boom or on the phone speaker), a 180 Hz body, a sharpness-driven softer top, bass punch
on limited masters, side ambience on narrow mixes, and a bounded `Grounding` stage (odd-order body saturation with a
level knee, downward-only top-band spike restraint). Every adaptive action is a registered policy rule
(SM-FOUND-1, SM-BODY-1, SM-SOFT-1, SM-PUNCH-1, SM-ATMOS-1, SM-GROUND-1, plus SM-HOUSE-1 and SM-TASTE-1) with an evidence
gate and a named test. **Learn this sound** (`TasteTarget`, `learnTaste`, `nativeTasteLearn`) lets the owner teach it
reference tracks on the phone (features only). `tools/mastering/` has the same-master test (`ab_compare.py`) and
corpus tools; the owner has no PC, so audio analysis happens in the working session on uploaded excerpts. Read
docs/SONIC_IDENTITY.md, docs/RESEARCH_GROUNDED_SOUND.md, docs/OWNER_REFERENCE_TASTE.md and docs/MASTERING_TRAINING.md.
Listening qualification is pending: do not claim listeners prefer it until the protocol in SONIC_IDENTITY.md has run.

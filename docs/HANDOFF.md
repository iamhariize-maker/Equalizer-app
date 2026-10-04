# Handoff — Svan (Svanam Shreshtham: Ultimate Sound)

Written at the end of a long Claude Code session so another agent (Codex cloud) can continue.
Repo: `iamhariize-maker/Equalizer-app`, branch **`ccr-208702a3-2mju42`** (not merged; no PR opened).
Start with `AGENTS.md`. This file has the detail.

## 1. The user and the goal
- Solo builder, **one Android phone (TECNO LH7n, Android 14), no PC.** Fan of Neutron Music Player
  (60–80-band EQ, "audiophile" 64-bit processing/resampling) and Wavelet/Poweramp EQ.
- Wants a **global (system-wide)** EQ — chosen over a Neutron-style standalone player on purpose.
- Likes the app so far ("I like the app"). Cares a lot about: audiophile depth, a polished **gold** look,
  brand ("Svan"), and being usable by non-geeks (hence the Sound tab with headphone tunings + three tuners).
- Quirks: numerology (3-6-9, Tesla) — it is deliberately woven into timings, icon, titles. Wants honest
  reporting; wants screenshots/APKs sent. Doesn't want to run commands unless unavoidable.

## 2. Current state (all verified in CI on a KVM emulator unless noted)
Latest verified-green commit: `11f26ef` (CI runs #23–#25 green: core + sanitizers, Android build/lint/tests,
release smoke test, **12/12 e2e PASS**). Local, not yet pushed at handoff time: boot-name sizing and the
flipped-d lift (see §6 item 1) — pushed with this file.

### Features built
- **DSP core (`core/`)**: 128-band parametric EQ (RBJ biquads, TDF-II, double), lock-free param updates;
  2/4/8× oversampled EQ (linear-phase Kaiser FIRs); rational polyphase resampler (Quality/Audiophile);
  TPDF + noise-shaped dither; auto headroom + Automatic Gain Protection; AutoEq parsers;
  `BassShaper` (punch↔sustain transient shaper); `StereoTuner` (mid/side vocal tuner + orchestral amp);
  headphone tuning maths (`tuning.cpp`: target−measurement, taste, dense-band fit).
- **Engine A / B + routing** as described in `AGENTS.md`. `CaptureCompat` probes per app whether capture
  carries audio (apps that opt out, e.g. Spotify, are un-muted and left on Engine A, never silenced).
- **UI (Compose)**: tabs **Sound | EQ | Presets | Hi-Fi | Lab**.
  - *Sound*: search ~8,850 AutoEq headphones (oratory1990, crinacle, Super Review, Rtings, Innerfidelity…),
    pick a signature (Harman, Harman lighter bass, Neutral, oratory1990, AutoEq in-ear, reviewer's published
    profile, imported target), taste sliders, 32/64/96 bands; **Bass tuner / Vocal tuner / Orchestral amplifier**
    with rotary knobs + presets.
  - *EQ ("EQ exten9ed")*: interactive response graph (drag nodes, tap to add, long-press to delete),
    band editor, graphic mode (10/15/31/64 faders).
  - *Presets*, *Hi-Fi* (engine start/stop, quality modes, dither, bits, gain staging), *Lab* (device probes, log).
  - Boot animation Svan → Svanam Shreshtham → ULTIMATE SOUND (0.3/0.6/0.9 s beats, 369 ms fade); brand line on every tab.
  - Icon: स्व (sva, from स्वनम्) in a brass yantra ring; adaptive + monochrome; Android 12+ splash uses it.
- **Release build**: R8-minified ~4.9 MB (arm64 + x86_64), signed with the **debug key** (preview only).

### Measured facts (all in tests; see docs/AUDIOPHILE.md)
- 16 kHz +9 dB bell vs ideal analog: error 5.56 dB @1× → 1.06 @2× → **0.24 @4×** → 0.06 @8×.
- Resampler 20.5 kHz passband loss: −5.9 dB (Quality) vs 0.0000 dB (Audiophile); alias rejection 104 vs 155 dB.
- HD 650 → Harman correction matches AutoEq's published one to 0.04 dB rms; 64-band fit 0.06 dB (10 bands: 0.84).
- Bass shaper: punch +8.3 dB attack/tail, sustain −5.2 dB; off = bit-exact. Vocal "Smooth": shrill voice −8.7 dB, mellow −0.0 dB.
  Orchestral amp: centred source bit-identical with dials at max; sides ±6 dB; side bass within 0.1 dB.
- Host CPU (cloud x86, one core, 80 bands stereo): 1× 3%, 2× 7%, **4× 14%**, 8× 29%. **Phone numbers unknown** — run `eqcore_bench` on-device.
- TECNO LH7n: `DynamicsProcessing` accepted 10–1024 bands, gains read back exactly, but each band costs ~1–2 ms
  (128 bands ≈ 100–250 ms; bulk set is no faster). Whether >31 bands are *audibly* resolved is **unknown**
  (the Visualizer reads pre-effect audio, so that probe was inconclusive).

## 3. Test/CI infrastructure (important — it is the only "device")
- `emulator-e2e` job: boots an Android 14 x86_64 KVM emulator (reactivecircus/android-emulator-runner), then runs
  `smoke_release.sh` (installs the R8 release build, opens all tabs, drives native paths), `diag.sh`, `e2e.sh`, `screens.sh`.
- `e2e.sh` plays a 1 kHz tone from the fake apps in `android/testsource/` and measures the final output mix with a
  Visualizer on session 0 (`MixMeter`). PASS/FAIL lines are computed from level differences (single processed copy
  vs double audio vs silence). The job fails on any `FAIL`.
- Scriptable app commands (adb, used by tests): `am start -n app.svan/.MainActivity --es cmd <probe|resolution|sessions|preset|
  start_capture|stop_capture|measure_mix|forget_verdicts|diag_capture|dump_lines|bass|tuners|tune> ...`; logs go to logcat tag `EqSpike`.
- Local container had no KVM; a software emulator took >20 min and looped on system_server watchdog. **Use CI, not a local emulator.**
- GitHub MCP tools were how artifacts were downloaded; if unavailable, use the Actions UI.

## 4. Hard-won gotchas (don't re-learn these)
1. `-Pandroid.injected.build.abi=…` (Android Studio flag) marks the APK **`android:testOnly="true"`** → installer says
   "package appears to be invalid". Build previews with plain `:app:assembleRelease`.
2. Without `<queries>` (Android 11+ package visibility) `getApplicationInfo()` fails for other apps → uid −1 → the
   per-UID capture check hears silence and wrongly caches BLOCKED. Fixed with `<queries>` + uid from the audio-service dump.
3. DynamicsProcessing instances on one session share one engine: Engine A's EQ and the source-mute must never both be attached.
4. `adb shell` flattens arguments: quote multi-word values for the *device* shell (`"'Sennheiser HD 650'"`).
5. `adb install -r` keeps app data → stale saved EQ skews later tests. Scripts uninstall first.
6. AutoEq correction math: apply AGP *after* the EQ (applying it before overshoots/pumps).
7. `pkill -f <pattern>` can match your own shell command line — match on binary path.
8. Visualizer on a player session sees **pre-effect** audio (also why capture-then-mute works). Use session 0 for the final mix.
9. Compose: reuse `android.graphics.Paint` objects in drawing code; allocating per frame stutters.
10. Mid/side crossovers must be Linkwitz-Riley low+high (not `x − low`), or the band gain leaks into the bass.

## 5. Known limitations (be honest about these in UI/docs)
- Orchestral amplifier needs mid/side → **Engine B only**; a centred solo instrument (sax, etc.) is treated like a vocal.
- Vocal tuner and bass "feel" on Engine A are coarse approximations (static EQ + multiband compressor).
- Spotify/Chrome etc. block capture → they only get Engine A.
- Engine B adds latency and needs a MediaProjection grant each session (an `appops PROJECT_MEDIA allow` via ADB avoids the prompt);
  discovering apps that don't broadcast their session needs `adb shell pm grant app.svan android.permission.DUMP`.
- Capture-based Engine B can't do bit-perfect/USB-DAC passthrough.
- All tuning thresholds were derived from **synthetic** signals; real music needs ear-tuning.

## 6. Open work, in priority order
1. **Verify the two visual fixes** on the next CI screenshots (`1-eq.png`, `boot-2.png`):
   - Boot: "Svanam Shreshtham" must be ONE centred line with side margins (font = width/12.8).
   - "EQ exten9ed": the flipped **d** (`Exten9edTitle` in `Brand.kt`, `translationY = −0.07em`) must read as a 9
     (bowl up, stem ending on the baseline). Previous values: −0.12em floated like a superscript; +0.03em hung below
     the baseline and read as a "q". Nudge in 0.02em steps. Note a flipped lowercase d is inherently q-like; if it
     still reads as "q", consider a real numeral 9 in the same serif with slightly smaller size, or a drawn glyph.
   - स्व icon on the Android 12+ system splash (`values-v31/themes.xml` uses `ic_launcher_foreground`).
2. **Get real-phone evidence** (the user will test; no PC): install `Svan-preview.apk` (release build), then ask for
   screenshots. Specific unknowns: does Engine B run stably for 30+ min (foreground service, screen off), battery cost per
   quality mode, audible latency, does R8 build behave (CI smoke test says yes on x86_64 only; arm64 untested).
3. **Per-app UI**: let the user see/override which engine each app uses (`SessionRouter.snapshot`, `CaptureCompat.all()`).
4. **Foreground-service robustness**: Engine A currently lives in the process (receiver-driven). Host it in a service so it survives backgrounding.
5. **Release readiness**: real signing key (currently debug), `targetSdk 35` foreground-service/MediaProjection policy text,
   privacy policy (INTERNET is used for AutoEq downloads only), `allowBackup` lint note, dependency bumps (lint lists newer Compose/AndroidX).
6. **App licence**: not chosen. Ask the user. Keep GPL code out (JamesDSP/RootlessJamesDSP are GPL; the repo's DSP is original).
7. **Sound-quality iteration by ear**: vocal smoothness threshold, bass shaper time constants, orchestral amp curves.
8. **Squiglink**: no data licence found for scraping; the app imports user-supplied curve files instead. If the user wants live
   Squiglink browsing, check each site's terms and the `phone_book.json` format first.
9. Nice-to-haves: Neutron-style crossfeed, loudness compensation, time-delay, convolution/FIR mode (core is ready for a FIR stage),
   AutoEq `GraphicEQ` for Engine A directly (currently re-fit to dense bells).

## 7. Reusable how-tos
- **Send the user an APK** (they install it directly): build `:app:assembleRelease` locally (no extra flags), copy
  `app/build/outputs/apk/release/app-release.apk` to `android/build-kit/Svan-preview.apk`. Large debug APKs (57 MB) failed to upload once; release is ~4.9 MB.
- **Look at CI screenshots**: download artifact `e2e-results`, view `screens/*.png`, `boot-*.png`.
- **Regenerate the icon**: `pip install fonttools uharfbuzz`, then follow the header comment in `tools/brand/make_icon.py` (Poppins SemiBold, OFL).
- **Add a native API**: C++ in `core/`, JNI in `android/app/src/main/cpp/jni_bridge.cpp`, Kotlin external in `NativeEngine.kt`
  (names must be `Java_app_svan_NativeEngine_native…`; R8 keeps them via `proguard-rules.pro`).
- **Add an e2e check**: add a scripted command in `MainActivity.handleCommand`, a step + `check` line in `scripts/e2e.sh`.

## 8. Attribution & licences in use
- AutoEq (MIT, Jaakko Pasanen): data/targets fetched at runtime; test fixtures in `core/tests/data/` with README.
- Poppins SemiBold (SIL OFL 1.1, Indian Type Foundry): launcher-icon glyph outlines. Mukta/Eczar were evaluated, not shipped.
- JamesDSP/RootlessJamesDSP (GPL-3): **read for technique only; no code copied.**

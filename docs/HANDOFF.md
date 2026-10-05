# Handoff — Svan (Svanam Shreshtham: Ultimate Sound)

Written at the end of a long Claude Code session so another agent (Codex cloud) can continue.
Repo: `iamhariize-maker/Equalizer-app`, branch **`ccr-208702a3-2mju42`** (not merged; no PR opened).
Start with `AGENTS.md`. This file has the detail.

## Player discovery and bass tuner — 2026-10-05 (current continuation)

The owner reports that Apple Music was detected earlier with Fosi Audio IM4, but is now missing over a
wired connection; Neutron has not appeared; and no player session appeared using Realme Buds Air 8
with LHDC either on or off. This is user-reported; no TECNO dump or logs are available. Treat the buds
as output routes and the player as the source session. The cause could be Android's report format,
session visibility, or an app-specific output path; it is not established.

The current source changes add explicit package visibility for Apple Music and Neutron, accept more
playback-dump field formats, and show local scan counts plus optional raw playback-config lines in
Hi-Fi → Music detection. This should distinguish no track, an unattachable track, and a parsed session
that has not been routed. Bass starter presets are reduced to +1.5–2.5 dB, with a new Clean impact
profile (+1.5 dB at 75 Hz, −0.8 dB at 250 Hz) and small-step, level-matched listening guidance on all
tuners. This is an experimental listening starting point, not a proven sound-quality improvement.

Verification for this continuation is pending. The local checkout is based on `b0527c3`, while the
remote feature branch has the previously validated routing/CI fixes through `36525ab`. Build and
review the new changes on that remote branch before delivering another preview. Physical TECNO and LG
V60 checks remain outstanding; do not claim Apple/Neutron or Realme-route compatibility until the
owner tests it.

> **Newest brief: `docs/CODEX_SVARAMANAS.md`** (Svaramanas sound intelligence: what's built, what's unverified,
> research agenda, next increments). Paste-in prompt: `docs/CODEX_PROMPT.md`.

## Clarity follow-up — 2026-10-04 (0.3.1 verification pending)

Resumed from Claude's green `e087533` / CI `37176877888`, preserving the foreground
media test-source fix. The earlier detection setup now passes all seven release
checks; its full audio log was reviewed before this follow-up.

Version 0.3 fixes reproduced native defects: retained protection attenuation now
recovers with a 250 ms release; bass character uses a complementary first-order
split so punch no longer cancels at its crossover; turning bass off restores exact
identity. Three new core regressions pass (47 total). See AUDIOPHILE.md.

Flat now resets every sound layer, with a visible Reset all sound action in
Presets and JVM regressions. Fresh defaults use system effects and float output;
existing saved preferences are preserved. Android system EQ requests a detailed
80 ms frame (10/40 ms options available), and UI no longer equates requested band
counts with actual resolution. Quality/dither controls are explicitly capture-only.
The release emulator script adds three actual-output checks: layered capture reset,
63 Hz +6 dB boost, and 63 Hz −6 dB cut. CI `37207558523` passed all prior audio
checks and reset, but 40 ms bass boost/cut measured +4.9/−4.6 dB and failed the
unchanged ±1 dB limit. Reviewed all screenshots: boot margins, numeral 9 and
actual स्व system splash are correct. Reference FFT/window simulation reproduced
the +4.9 dB result and predicts +5.57 dB at 80 ms; 0.3.1 keeps faster options and
uses 80 ms for Detailed. New CI pending at writing. Follow the required
checks, inspect CI/screens, then deliver the tested APK; retest TECNO/YT Music over
Bluetooth. The user's current sound preference cannot be established by emulator.

## Continuation — 2026-10-04

Phone report after `55f84f8` / CI `37169778164` (all tests green and screenshots
reviewed): installed successfully, but YouTube Music remained completely
unprocessed. Screenshots show zero detected apps; the running capture service
was processing silence at 29.4% measured DSP load. Wi-Fi is available. Other
players are available on a different phone but have not yet been tested.

Current follow-up addresses the delivery-to-audio gap, with CI pending at writing:
- Runtime session receiver in SystemEqService accepts general player broadcasts.
  The old tests explicitly addressed Svan, hiding Android's manifest-only receiver
  limitation. New checks omit the package and revoke DUMP.
- Phone-only Music detection setup uses Shizuku 13.1.5 API (MIT) and a fixed,
  short-lived UserService to grant only Svan's DUMP permission. Normal operation
  remains in Svan's own process; the helper can be stopped afterwards.
- Discovery refreshes after a live grant and polls every five seconds as backup.
  Duplicate playback records preserve a playing sibling and all capture opt-outs.
- No admitted source skips DSP/dither and produces zero output; input/output
  signal peaks make silence visible. EQ status no longer calls zero apps active.
- `detection_release.sh` exercises the R8 grant service against pinned official
  Shizuku 13.6.0 under shell identity, already-playing discovery, helper shutdown,
  single-copy 4× capture, graphic +6 dB, bypass, and parametric −12 dB.
- Version 0.2 requires another phone check after setup. Android/OEM behavior,
  Bluetooth delay and perceived sound quality are still not established by CI.

The original priorities and previous implementation history follow.

User now reports heavy delay / echo-like doubled playback with **YouTube Music
on Bluetooth** on the TECNO LH7n. Other players/outputs are untested. They want
Svan’s own identity, measurable feature effectiveness and fewer unsupported claims.
Treat this as unresolved phone evidence, even when synthetic emulator tests pass.

Commit `0ff00da` passed CI run `37168656837`: core/sanitizers, Android checks,
release smoke and 20/20 audio checks. Screenshots confirm readable 9, boot margins
and the actual स्व system splash. Review also exposed vertical swipes editing
Sound knobs instead of scrolling. Follow-up uses horizontal dial drags, adds
accessible range actions and a CI state-preservation check for scrolling; its
CI verification is pending at this writing.

Changes in this continuation:
- Boot name margins confirmed in `a48a8bd` screenshots. Its flipped d still looked
  like q; now using a serif numeral 9 with accessible “EQ Extended”. The system
  splash gets a new screenshot attempt; confirm the actual icon image.
- Engine B now captures only known UIDs whose routed sessions are all muted.
  Unknown audio is excluded. Mute loss/conflicting UID routes stop capture.
  Smaller explicit input/output buffers; Hi-Fi shows output queue, DSP load and
  underruns, explicitly excluding total Bluetooth/capture latency.
- Per-app Auto/System effects choices in Hi-Fi. Changing one stops capture;
  a new explicit grant applies it. Global System effects only also stops Engine B.
- Engine A’s foreground lifetime/discovery moves into SystemEqService, with
  notification Stop and a Hi-Fi start/stop control. Failed/dead effects no longer
  terminate the update collector; unavailable processing is shown in the app row.
- Measured duplicate headroom attenuation fixed: −6 dB preamp plus +6 dB bell no
  longer becomes −12 dB preamp. Headroom/protection toggles now apply to Engine A.
  Engine B protection resets on EQ edits, so bypass does not retain old overload loss.
- DSP capacity reserved at 256 stages for 128 manual bands plus up to 96 tuning
  bands and tuners. Previously later non-neutral layers could be silently dropped.
  Native processing remains allocation-free in steady playback.
- Removed comparison branding; qualified mid/side and synthetic-test claims.
  No voice/instrument recognition or total phone-latency guarantee is claimed.
- New core regressions bring the suite to 44 tests. E2E adds boost/bypass, per-app
  overrides, global mode, background EQ and unknown-audio exclusion (20 checks).
- CI uploads `Svan-preview` (release APK) as well as debug and emulator artifacts.
  `-PsvanProduction=true` requires owner-provided signing inputs; no production
  key created, no store publication. See RELEASE_READINESS.md and PRIVACY.md.

Remaining work, in original priority order:
1. Inspect every new CI log and screenshots after each push; confirm numeral 9,
   boot margins, actual system-splash icon and new Hi-Fi rows on the small emulator.
2. Deliver latest preview and get phone retest using PHONE_VALIDATION.md. Detection
   without DUMP, Bluetooth delay/echo, arm64 behavior and 30-minute screen-off/battery
   stability remain unresolved. Undetected apps are excluded from Engine B, not
   magically processed; the UI must say so.
3. Per-app controls implemented; refine based on phone evidence, especially
   multiple sessions sharing one UID (currently fails safely back to Engine A).
4. Foreground lifetime implemented; actual OEM kill/restart behavior needs phone tests.
5. Signing inputs, owner-approved privacy policy URL/contact, Play declarations,
   dependency upgrades and OEM backup review remain release gates. No blanket
   dependency update in the audio correctness change.
6. Licence question sent to user; no answer yet. Keep original-code licence undecided.
7. Ear-tune only after stable routing and level-matched music evidence. Do not
   increase all dial gains or invent thresholds to imply perceptual improvement.
8. No live Squiglink scraping: no applicable data licence established; user file
   import remains available.
9. Crossfeed/loudness/delay/convolution remain future work, after the reported
   playback/efficacy problems are verified on the real phone.

Baseline CI `37166890912` failed 1/12 checks: the source was stopped while a final
“tuners off” dynamics rebuild attempted attachment to its closed session. Added
settling time before source close and lifecycle serialization/resource cleanup.
The actual level and routing checks plus release smoke passed in that run.

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

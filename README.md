<div align="center">

# Svan · स्वन्

**A free system-wide equalizer for Android, built for sound quality.**
64-bit DSP · up to 256 parametric stages · AutoEq headphone correction · no root needed

![Status: beta](https://img.shields.io/badge/status-beta-orange.svg)
![Android 10+](https://img.shields.io/badge/Android-10%2B-green.svg)
[![CI](https://github.com/iamhariize-maker/Equalizer-app/actions/workflows/ci.yml/badge.svg)](https://github.com/iamhariize-maker/Equalizer-app/actions/workflows/ci.yml)

[**⬇ Download the beta**](https://github.com/iamhariize-maker/Equalizer-app/releases/tag/v0.5.5-beta.1) ·
[**Report your device**](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=device-report.yml) ·
[Compatibility](docs/COMPATIBILITY.md) ·
[Setup guide](docs/SETUP.md)

</div>

*Svan* (स्वन्) is Sanskrit for **sound**; the full name is *Svanam Shreshtham: Ultimate Sound*.

> **Beta: testers wanted.** The DSP engine is measured and tested, and the app is verified on Android emulators.
> Real-phone compatibility is still being mapped, so a
> [device report](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=device-report.yml) (about a minute) is the most useful thing you can do,
> whether Svan worked for you or not.

<p align="center">
  <img src="docs/images/setup/first-run.png" width="240" alt="Svan Sound screen with a flat EQ curve">
  &nbsp;&nbsp;
  <img src="docs/images/setup/system-effects.png" width="240" alt="Svan Hi-Fi screen showing a player found and routed">
</p>
<p align="center"><sub>Captured on an Android emulator with a synthetic test player, not a real phone or music app.</sub></p>

## Why Svan

Most Android equalizers are tied to one music app, or need root. Svan applies one EQ to the apps you already use.

- **System-wide.** One EQ for eligible audio from your music apps, video apps and browsers.
- **Headphone correction.** Search AutoEq profiles for your headphones, or import `ParametricEQ.txt` and `GraphicEQ.txt` files.
- **Serious parametric EQ.** Up to 256 active stages per channel, independent left and right, with peak, shelf, high/low-pass, band-pass, notch and all-pass filters.
- **64-bit processing, oversampling, clipping protection.** Audiophile engine only. 2x/4x/8x oversampling reduces the high-frequency "cramping" of digital EQ, with auto headroom, gain protection and TPDF or noise-shaped dither.
- **Blind listening comparison.** A built-in tool so you can check whether a change really sounds better.
- **No root.** Optional [Shizuku](https://shizuku.rikka.app/) setup helps Svan find players that don't announce themselves, and can be stopped afterwards.
- **No ads and no analytics SDK.** Audio is processed on your phone. See the [privacy notice](docs/PRIVACY.md).

## Two engines

| | System effects (recommended) | Audiophile engine (experimental) |
|---|---|---|
| How | Android `DynamicsProcessing` on other apps' audio sessions | Captures playback, runs the 64-bit C++ chain, plays it back |
| Strengths | Low latency; works with apps that block capture | Full DSP chain, oversampled EQ, dither |
| Trade-offs | Gain-per-band only; Android controls the precision | Adds delay (more on Bluetooth); needs a capture permission each session; no bit-perfect or USB-DAC passthrough |

Fresh installs start with System effects, Flat settings and 0 dB preamp. Details: [ARCHITECTURE.md](docs/ARCHITECTURE.md), [AUDIOPHILE.md](docs/AUDIOPHILE.md).

## Quick start

1. Download the APK and `SHA256SUMS` from [Releases](https://github.com/iamhariize-maker/Equalizer-app/releases/tag/v0.5.5-beta.1) and run `sha256sum -c SHA256SUMS`,
   or compare the APK's SHA-256 with a trusted hash tool. **Never disable Play Protect.** If Android warns or blocks the install, stop and
   [report the exact message](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=bug_report.yml).
2. Open Svan and play music. No setup is required to start.
3. In **Hi-Fi → Is it working?** check that your player and output route are shown.
4. If your player isn't reachable, use **Fix music detection** (optional Shizuku wizard). See [docs/SETUP.md](docs/SETUP.md).
5. In **Presets**, search your headphones for an AutoEq correction, or import a profile file.
6. Tell us how it went: [file a device report](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=device-report.yml).

Requires Android 10 or newer (arm64-v8a or x86_64). Coming from an earlier preview? See [moving from preview](docs/MOVING_FROM_PREVIEW.md) before uninstalling.

## Known limitations (beta)

- Real-phone, OEM, Bluetooth and player compatibility are unverified. Every row of [COMPATIBILITY.md](docs/COMPATIBILITY.md) says how it was verified.
- On one test phone the Audiophile engine sounded delayed or doubled over Bluetooth, and some players weren't detected. Use System effects if you hear it.
- Apps that block audio capture can only use System effects.
- Measured results in the docs come from synthetic tests, not listening tests or total phone latency.
- This is a beta, not a store release.

## The DSP core

`core/` is a portable C++17 DSP library, shared by the Android app and any future player mode.

- **Parametric EQ:** up to 256 active stages per channel, independent L/R (Android allows 128 manual bands plus tuning and tuners). Band changes never block the audio thread.
- **AutoEq import:** `ParametricEQ.txt` and `GraphicEQ.txt` formats.
- **64-bit processing:** the whole chain runs in double precision.
- **Oversampled EQ** (2x/4x/8x), a **resampler** with Quality / Audiophile settings (portable core only; Android capture stays at 48 kHz), **dither**, **auto headroom** and **Automatic Gain Protection**.
- **Tested:** 98 self-contained unit tests, also run under ASan and UBSan in CI.

## Build and test the core

```sh
cmake -S core -B build/core && cmake --build build/core -j
./build/core/eqcore_tests
./build/core/eqcore_bench
# Sanitizers:
cmake -S core -B build/asan -DEQCORE_SANITIZE=address,undefined -DCMAKE_BUILD_TYPE=Debug && cmake --build build/asan -j && ./build/asan/eqcore_tests
```

## Build the Android app

Requires the Android SDK with NDK 27.0.12077973 and CMake 3.22.1. Gradle installs them automatically if the licences are accepted.

```sh
cd android && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# Optional, enables enhanced session detection (the Wavelet approach):
adb shell pm grant app.svan android.permission.DUMP
```

Then follow [docs/SPIKE.md](docs/SPIKE.md).

For phone-only setup on Android 11+, use Hi-Fi → Music detection and its Shizuku
guide. Once the user-authorized setup grants detection access, Shizuku and wireless
debugging can be stopped. See [phone validation](docs/PHONE_VALIDATION.md).

The optional [Shizuku API](https://github.com/RikkaApps/Shizuku-API) is MIT licensed,
Copyright (c) 2021 RikkaW. Its full notice ships in `assets/licenses/Shizuku-API-MIT.txt`.

## Reporting problems and contributing

- **Device reports:** [use the form](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=device-report.yml). It's the fastest way to help.
- **Bugs:** [use the bug form](https://github.com/iamhariize-maker/Equalizer-app/issues/new?template=bug_report.yml). Issues are public, so redact private details.
- **Code:** The source is public to read but not licensed for reuse, so code pull requests are not being accepted. See [CONTRIBUTING.md](CONTRIBUTING.md).
- If Svan is useful to you, a ⭐ helps other people find it.

## Licence

Svan's original code is **All rights reserved**, per the owner's decision. See [LICENSE](LICENSE).
The beta is free to use for personal evaluation. Third-party materials retain their own licences and attribution;
no GPL implementation is copied.

## Brand assets

The launcher mark is a modern **स्व** (sva, as in स्वनम् — "Svan") generated by
`tools/brand/make_icon.py` from **Poppins SemiBold** glyph outlines
(Indian Type Foundry, [SIL Open Font License 1.1](https://openfontlicense.org)),
shaped with HarfBuzz, with a continuous brass shirorekha inside the yantra ring.

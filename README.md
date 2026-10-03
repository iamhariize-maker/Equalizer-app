# Equalizer

A **global (system-wide) audiophile equalizer for Android**, inspired by
Neutron Music Player's DSP and settings, Wavelet and Poweramp Equalizer.

Status: **early spike.** The DSP core is real and tested; the Android app is a
diagnostics shell for answering the open platform questions on real phones.

## Layout

| Path | What |
|---|---|
| `core/` | Portable C++17 DSP engine (64-bit). Shared by Android now and iOS/player mode later. |
| `core/tests/` | 22 self-contained unit tests (no framework needed). |
| `core/bench/` | CPU benchmark per quality mode. |
| `android/` | Kotlin app + JNI bridge: Engine A (system effects), Engine B (capture), probes. |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [Audiophile mode](docs/AUDIOPHILE.md), [Spike plan](docs/SPIKE.md). |

## The DSP core

- **Parametric EQ:** up to 128 bands per channel, independent L/R. Peak, shelf, high/low-pass, band-pass, notch and all-pass filters.
- **Thread safety:** band changes are applied without ever blocking the audio thread.
- **AutoEq import:** `ParametricEQ.txt` and `GraphicEQ.txt` formats.
- **64-bit processing:** the whole chain runs in double precision.
- **Oversampled EQ** (2x/4x/8x): removes the high-frequency "cramping" distortion of digital EQ.
- **Resampler** with Neutron-style **Quality / Audiophile** settings.
- **TPDF and noise-shaped dither.**
- **Auto headroom** (predicts boosts from the curve) and **Automatic Gain Protection** (catches actual overloads).

## Build and test the core

```sh
cmake -S core -B build/core && cmake --build build/core -j
./build/core/eqcore_tests
./build/core/eqcore_bench
# Sanitizers:
cmake -S core -B build/asan -DEQCORE_SANITIZE=address,undefined -DCMAKE_BUILD_TYPE=Debug && cmake --build build/asan -j && ./build/asan/eqcore_tests
```

## Build the Android spike

Requires the Android SDK with NDK 27.0.12077973 and CMake 3.22.1. Gradle installs them automatically if the licences are accepted.

```sh
cd android && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# Optional, enables enhanced session detection (the Wavelet approach):
adb shell pm grant dev.equalizer.app android.permission.DUMP
```

Then follow [docs/SPIKE.md](docs/SPIKE.md).

## Licence

Not chosen yet. Note that reusing JamesDSP/ViPER code would require GPL;
this core is written from scratch so the choice stays open.

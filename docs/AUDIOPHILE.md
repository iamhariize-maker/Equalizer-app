Latest native quality and listening work: [0.5.5 quality lab](QUALITY_LAB_0.5.5.md).

# Audiophile processing

Svan has an original double precision DSP chain and a separate Android system-effects path.
The measurements below describe synthetic core tests, not listening quality or total phone latency.
The user reports delayed/echo-like playback on YouTube Music over Bluetooth; this needs phone retesting.

## 1. 64-bit processing

The whole chain runs in IEEE double precision: filter coefficients, filter state,
oversampling, resampling and gain. This describes Engine B only. Android controls Engine A’s precision. Phone CPU cost is not established.

## 2. Audiophile resampling (`core/src/resampler.cpp`)

This is a rational polyphase converter with a Kaiser-windowed sinc kernel. Each
polyphase branch is normalised to exactly unit gain, so there's no
phase-dependent "interpolation noise".

| | Quality | Audiophile |
|---|---|---|
| Flat to | 0.86 × Nyquist | 0.95 × Nyquist |
| 20.5 kHz tone, 44.1 → 48 kHz | **−5.9 dB** | **0.0000 dB** |
| Alias rejection (23 kHz, 48 → 44.1 kHz) | **104 dB** | **155 dB** |
| Kernel length | 92 taps/phase | 368 taps/phase |
| CPU, 44.1 → 96 kHz stereo (host) | 1.9 % of a core | 9.0 % of a core |

The passband is transparent to < 0.001 dB in both modes (100 Hz–18 kHz, all tested rate pairs).

### Where resampling applies in a *global* EQ

- **Input side:** in capture mode, Android's own mixer has already resampled
  each app's audio to the capture rate before we see it. We can't replace that
  step without root.
- **Output side:** the resampler applies when *our* output rate differs from
  the capture rate. That covers USB DACs and hi-res output (e.g. 48 → 96/192 kHz),
  and also the future player mode, where we decode files ourselves.
- **Not wired into the Android spike yet:** it runs at 48 kHz in and out.

## 3. Oversampled EQ: the "Audiophile" quality mode

Digital EQ filters bend out of shape as they approach Nyquist ("cramping"). Running the EQ
at 4x/8x the sample rate fixes it. Measured on a +9 dB bell at 16 kHz on
44.1 kHz material, against the ideal analog response:

| Mode | Worst deviation 8–19.5 kHz | CPU (80 bands, stereo, host) | DSP-only latency |
|---|---|---|---|
| Efficient (1x) | **5.56 dB** | 2.6 % | 0 ms |
| High Quality (2x) | 1.06 dB | 6.8 % | 0.8 ms |
| **Audiophile (4x)** | **0.24 dB** | 13.7 % | 1.1 ms |
| Extreme (8x) | 0.06 dB | 26.5 % | 1.4 ms |

The oversampling filters are linear-phase with 120–140 dB image rejection
(132 dB measured at 2x). DSP latency is an exact integer number of samples. It excludes capture, Android buffers and Bluetooth transport.

**Host vs. phone:** the CPU figures come from a cloud x86 core. Expect a phone's
efficiency cores to be several times slower, so measure with `eqcore_bench` on
the device before picking Extreme as a default.

## 4. Dither

- **TPDF dither (±1 LSB):** the measured error is exactly 1/4 LSB² with zero mean. It preserves a sine 0.4 LSB in amplitude that plain rounding erases completely.
- **Noise-shaped TPDF (Extreme mode):** moves noise out of the midrange. Measured −16.3 dB at 1 kHz and +6.8 dB at 20 kHz, against theory of −16.9 / +5.9 dB.

## 5. Automatic Gain Protection + auto headroom

0.5.4: Svaresa keeps both protections active while controlling the engine; manual choices are
preserved. Live EQ transitions crossfade over 10 ms and gain changes ramp over 10 ms; editing
does not reset the protector. Protection changes no longer rebuild capture DSP. See
[measured transition regressions and limits](ENGINE_QUALITY_0.5.4.md).

- **Auto headroom (predictive):** adds only the attenuation still needed after the user/preset preamp. A −6 dB preamp with a +6 dB bell stays at −6 dB, instead of the previous −12 dB.
- **AGP (reactive):** catches sample overloads, links channel gain, and smoothly
  recovers toward unity with a 250 ms release after overload ends. A sustained
  +12 dB boost on a 0.9 sine requires about 11.18 dB reduction. The new regression
  verifies recovery to within 0.01 dB after three seconds of quiet material.
  This is sample-peak protection, not a true-peak limiter. It adds no lookahead.

## 6. Clarity corrections (0.3)

- Bass character now uses a first-order complementary split. The previous LR4
  low-pass/dry recombination lost roughly 6 dB during a 120 Hz attack in a host
  probe. Regressions check attack energy at 90/120/150/180 Hz, 44.1/48 kHz, and
  exact off-state identity after active shaping. Punch/sustain remain optional.
- The six-hit synthetic kick test now measures +13.7 dB attack/tail for maximum
  punch and −8.1 dB for maximum sustain; these are not listening-quality scores.
- Built-in Flat and Reset all sound clear every shaping layer, including headphone
  correction. Other presets continue to preserve independent layers.
- Fresh settings use system effects and unquantized float capture output. Saved
  user preferences remain intact. System effects request an 80 ms frame for better
  bass resolution; 10 and 40 ms options trade resolution for less delay. Actual FFT
  sizing, response, and end-to-end delay depend on the phone. Increasing curve
  points alone cannot create independently resolved frequency bands.
- CI `37207558523` exposed insufficient 63 Hz resolution at 40 ms: +6/−6 dB
  bells produced +4.9/−4.6 dB, outside the unchanged ±1 dB acceptance limit.
  Android's reference FFT/window model independently predicts +4.90 dB at 40 ms
  and +5.57 dB at 80 ms for this curve. Version 0.3.1 therefore uses 80 ms for
  Detailed; actual-output verification is required before delivery. This improves
  resolution without adding gain compensation or promising perfect parametric
  response from Android's gain-per-band effect.
- CI measures post-effect 63 Hz boosts/cuts and a layered capture reset in addition
  to the existing routing and gain checks. Actual TECNO/Bluetooth sound still needs
  the user's retest; no bit-perfect or universally superior-output claim is made.

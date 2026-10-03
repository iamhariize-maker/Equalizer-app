# Audiophile processing

Neutron's settings that inspired this:
- **64-Bit Processing:** "highly accurate 64-bit audio processing".
- **Resampling:** *Quality* ("moderate CPU load, good precision of audio interpolation") vs. *Audiophile* ("higher CPU load, best precision of audio interpolation").
- **Automatic Gain Protection:** "auto-detect overloaded audio and reduce the Preamp gain".

This project implements all three, plus oversampled EQ, which Neutron doesn't
expose as a separate setting. Every claim below is a measured test result
(`core/tests/test_main.cpp`), not a marketing number.

## 1. 64-bit processing

The whole chain runs in IEEE double precision: filter coefficients, filter state,
oversampling, resampling and gain. It's always on. On phone CPUs the cost is
small compared with the filtering work, so there's no 32-bit mode to fall back to.

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

| Mode | Worst deviation 8–19.5 kHz | CPU (80 bands, stereo, host) | Latency |
|---|---|---|---|
| Efficient (1x) | **5.56 dB** | 2.6 % | 0 ms |
| High Quality (2x) | 1.06 dB | 6.8 % | 0.8 ms |
| **Audiophile (4x)** | **0.24 dB** | 13.7 % | 1.1 ms |
| Extreme (8x) | 0.06 dB | 26.5 % | 1.4 ms |

The oversampling filters are linear-phase with 120–140 dB image rejection
(132 dB measured at 2x). Latency is an exact integer number of samples.

**Host vs. phone:** the CPU figures come from a cloud x86 core. Expect a phone's
efficiency cores to be several times slower, so measure with `eqcore_bench` on
the device before picking Extreme as a default.

## 4. Dither

- **TPDF dither (±1 LSB):** the measured error is exactly 1/4 LSB² with zero mean. It preserves a sine 0.4 LSB in amplitude that plain rounding erases completely.
- **Noise-shaped TPDF (Extreme mode):** moves noise out of the midrange. Measured −16.3 dB at 1 kHz and +6.8 dB at 20 kHz, against theory of −16.9 / +5.9 dB.

## 5. Automatic Gain Protection + auto headroom

- **Auto headroom (predictive):** pre-attenuates by the curve's largest boost.
- **AGP (reactive):** catches what prediction can't, such as hot masters and
  inter-sample peaks. On an overload it scales the chunk to −0.1 dBFS and keeps
  the reduction. Measured: a +12 dB boost on a 0.9 sine reduces by 11.18 dB
  (theory 11.2 dB). The output never exceeds the ceiling, and the gain doesn't pump.

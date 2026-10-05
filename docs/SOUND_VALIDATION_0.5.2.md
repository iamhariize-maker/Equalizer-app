# 0.5.2 sound validation

Measured synthetic DSP behavior, not a listening verdict or an IM4 calibration. Run
`cmake -S core -B build/core && cmake --build build/core -j && ./build/core/eqcore_tests`.
Native tests run in release and with ASan/UBSan; thread-update tests also run under TSan in CI.

## Changes and evidence

- Analysis uses mid **and** side power. Previously side-only music never became valid; it now
  produces the same loudness, cutoff and tonal deviations as its equivalent centre signal.
- Complete guide matching includes static intimacy, instrument focus and stereo space. Live
  filter coefficients are shared with the power model, including the complex LR4 crossover sum;
  measured mid/side responses agree within 0.02 dB at 80/180/500/1200/3000/10000 Hz.
- Guide strength scales all suggestions; zero is neutral. All 6,876 tested feel/category/strength
  combinations retain the 6 dB positive-EQ budget and ±3 dB individual EQ limit.
- Guide profiles pair foreground presence with neighboring masking cuts. Svaresa's measured
  excess slope increases from 0.75 to 1.1; correction limits stay at 4 dB, with the same thresholds,
  clean-source dead bands, codec ceiling, mono and limited-master guards.
- The controller matches the complete guide/context curve after adaptive slewing. The old
  quiet-listening trim (35% of the largest shelf) is removed. No normalization or compressor
  makeup is added to make the result louder.

## Full-path measurements

48 kHz deterministic random-phase, 240-frequency logarithmic multisines. Input/output loudness
measured separately by the K-weighted analyser after 6–8 seconds. Every suggested bass and stereo
processor is enabled. Auto headroom and gain protection are disabled in matching tests to avoid
hiding level errors; a separate strong-guide test enables protection and asserts output ≤0.989.

| Case | Measured change |
|---|---:|
| Eight pink/dark guide cases (Bright/Warm/Punchy/Intimate, multiple picks) | -0.25 to +0.12 dB loudness |
| Dark centre + bright side, strong Space/Strings | +0.05 dB loudness |
| Same split spectrum, strong Intimate/Voice/Winds | +0.01 dB loudness |
| Guide + 6 dB quiet bass / 3 dB quiet treble / night presence, system-style path | -0.04 dB loudness |
| Same combined curve, stereo path | -0.03 dB loudness |
| Side-only low-mid excess through Svaresa | mud 4.03 → 2.96 dB; loudness ~0.00 dB |

Foreground contrast is the output change in the named region **relative to** its masking region,
measured from the actual output spectrum with the whole guide running (mono reference):

| Choice | Foreground / masking region | Contrast change |
|---|---|---:|
| Voice | 3 kHz / 250 Hz | +3.02 dB |
| Guitars | 2.4 kHz / 350 Hz | +2.83 dB |
| Strings | 2.2 kHz / 300 Hz | +1.77 dB |
| Brass/winds, including sax focus | 1 kHz / 300 Hz | +2.38 dB |
| Bass | 80 Hz / 250 Hz | +1.99 dB |
| Piano | 4.5 kHz / 400 Hz | +1.70 dB |

## Limits and phone validation

These are shared-band tonal changes, not instrument separation. M/S placement is frequency-dependent;
voice and instruments can occupy both channels. No headphone sensitivity or measured IM4 response
was assumed. Negative matching trim can reach -18 dB for extreme stacked stereo focus; positive
makeup is still capped at +1.5 dB. Dynamic de-harshing and bass-envelope gain cannot be predicted
exactly from averaged spectra. Guide-only bass character is restrained to 0.15; manual settings keep
their existing range. A manual EQ/tuner/profile combination and safety headroom can change the final
loudness beyond the isolated guide measurements. Peak protection is not an inter-sample true-peak
limiter. Oversampling modes and Android system effects still have their existing route/resolution limits.

System effects have no live source-analysis tap. Matching there uses a reference spectrum and is
labelled an estimate; Auto master uses output, volume and night context. Only valid captured audio
permits mix-driven correction. Night level-evening exists in system effects; capture gets night tone
changes. Subjective clarity/dynamics, IM4 listening, mainstream streaming sessions, Bluetooth echo,
HiOS survival and Play Protect acceptance of this build require real-phone evidence.

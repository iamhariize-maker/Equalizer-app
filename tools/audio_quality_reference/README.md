# Audio quality engineering references

These files accompany [the design](../../docs/audio-quality/DESIGN.md) and
[the Sol implementation prompt](../../docs/CODEX_AUDIO_QUALITY_IMPLEMENTATION_PROMPT.md).
They do not modify, build or sign the Android application. The released APK is unchanged.

- `stereo_phase_probe.cpp`: reproduces the current side-only crossover phase defect
  against the real `core/src/stereo.cpp`, including a left-only 180 Hz test.
- `quality_policy.h`: original C++17 kernels for explicit control ownership, residual
  eligibility, an exact quadratic side-energy constraint, Bass Resolve's decision
  limits, combined reduction budgets and sample-rate-aware oversampling.
- `test_quality_policy.cpp`: deterministic and randomized mathematical checks.
- `reference_dsp.py`: offline dry-plus-delta WOLA and decorrelated-residual prototype.
  It returns latency-aligned audio. A streaming implementation must account for its
  complete fixed delay. It deliberately has no semantic vocal classifier or HRTF.
- `test_reference_dsp.py`: identity, panning, mono, pure-side and residual-lift checks.

Run from the repository root (place executables outside the source tree):

```sh
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
  tools/audio_quality_reference/test_quality_policy.cpp -o /tmp/svan-quality-policy
/tmp/svan-quality-policy
python3 -m unittest discover -s tools/audio_quality_reference -p 'test_*.py'
g++ -std=c++17 -O2 -Icore/include \
  tools/audio_quality_reference/stereo_phase_probe.cpp core/src/stereo.cpp core/src/biquad.cpp \
  -o /tmp/svan-stereo-probe
/tmp/svan-stereo-probe
```

Python requires NumPy; it is a numerical reference, not an Android dependency.
The C++ reference is independent of production eqcore and allocates nothing in its
decision functions. `DbSmoother` assumes a positive, validated sample rate and time
constants. `oversamplingFor` is for the six 44.1/48 kHz family rates in the design.

**Limits:** these tests establish arithmetic and specific synthetic behavior. They do
not validate an Android streaming WOLA implementation, perceptual quality, vocal/stem
separation, a real Bass Resolve detector, transient handling, phone CPU/battery, or a
high-resolution path through Amazon/Spotify. The design lists those implementation gates.
Do not wire the Python prototype into a live audio callback or treat its default
thresholds as a finished mastering model.

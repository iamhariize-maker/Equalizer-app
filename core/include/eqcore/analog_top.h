#pragma once
// Analogue top: level-dependent softening of the 6-12 kHz band, the way tape and transformers ease loud treble.
//
// The band (a second-order band-pass at 8.5 kHz, Q 0.6) is followed by a 30 ms power envelope. Above a knee of
// -30 dBFS (band RMS) its gain falls by 0.15 dB per dB, at most 2.5 dB at full depth: a sustained crash cymbal or a
// bright, loud wash softens, while quiet air and short clicks (too brief to move a 30 ms envelope) pass unchanged.
// Nothing is ever lifted. The cut is a parallel band subtraction, x + (gain - 1) * band(x), with the same gain on both
// channels, so the image cannot move. depth 0 is a bit-exact bypass. Knee, slope and cap are design values, not
// listening results (docs/BUILD_BRIEF_0.5.14.md WP10). No look-ahead; allocation-free and wait-free.
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

class AnalogTop {
 public:
  explicit AnalogTop(double sampleRate);

  // Any thread; clamped to 0..1, non-finite counts as 0.
  void setDepth(double depth);
  void reset();
  // In place; right may be null (mono). Allocation-free.
  void process(double* left, double* right, int frames);
  // Last applied reduction, dB (<= 0).
  double reductionDb() const { return reductionDb_.load(std::memory_order_relaxed); }

 private:
  struct Bq {
    BiquadCoeffs c;
    double z1 = 0, z2 = 0;
    double run(double x) {
      const double y = c.b0 * x + z1;
      z1 = c.b1 * x - c.a1 * y + z2;
      z2 = c.b2 * x - c.a2 * y;
      return y;
    }
  };
  std::atomic<double> depth_{0.0};
  std::atomic<double> reductionDb_{0.0};
  Bq bandL_, bandR_;
  double power_ = 0.0, gain_ = 1.0, aPower_ = 0.0, aGain_ = 0.0;
};

}  // namespace eqcore

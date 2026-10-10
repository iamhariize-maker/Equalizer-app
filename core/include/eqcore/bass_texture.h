#pragma once
// Bass texture: level-gated odd harmonics for the bass notes, so the bass has weight and
// definition on systems that cannot reproduce 40-80 Hz (phones, small speakers, earbuds).
//
// Why: a bass note is heard through its harmonics as well as its fundamental (the missing-fundamental
// effect). Adding a few odd harmonics makes the line audible without adding sub-bass energy.
//
// How (mid channel only, so the image is untouched):
//   sub   = 4th-order low-pass at 150 Hz of the mid signal
//   shape = tanh(k * sub) / k, an odd-order shaper with small-signal gain 1; drive k rises with the
//           note's level (0.5 quiet .. 2.5 at -12 dBFS), so quiet notes stay linear and loud ones gain weight
//   wet   = high-pass 30 Hz of (shape - N(level) * sub)
// N(level) is the shaper's own fundamental gain for that level (a precomputed describing function), so
// the fundamental is not compressed: wet carries only the generated harmonics. Output adds depth * wet
// equally to L and R.
//
// Properties (all tested in core/tests/test_main.cpp):
//   - depth 0 is a bit-exact bypass;
//   - odd-order only (tanh is odd), no even-order grit (docs/RESEARCH_GROUNDED_SOUND.md section 3);
//   - at -12 dBFS, full depth puts the 3rd harmonic about -32 dBc (measured); at -50 dBFS it is far below audibility;
//   - the side channel and content above ~1 kHz are not changed by the texture itself.
// No look-ahead, so no latency. Allocation-free and wait-free on the audio thread.
#include <array>
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

class BassTexture {
 public:
  explicit BassTexture(double sampleRate);

  // Any thread; picked up at the next block (20 ms ramp). Clamped to 0..1; non-finite counts as 0.
  void setDepth(double depth);
  double depth() const { return target_.load(std::memory_order_relaxed); }

  void reset();

  // In place. right may be null (mono). Allocation-free.
  void process(double* left, double* right, int frames);

  // Fundamental describing-function gain of the shaper at amplitude `level` (1 at zero level). Exposed for tests.
  static double fundamentalGain(double level);

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
  static constexpr int kTableSize = 129;  // levels 0..kTableMax in equal steps
  static constexpr double kTableMax = 2.0;
  static const std::array<double, kTableSize>& table();

  std::atomic<double> target_{0.0};
  double depth_ = 0.0;
  double aDepth_ = 0.0, aUp_ = 0.0, aDown_ = 0.0;
  double level_ = 0.0;
  Bq lowLp_[2], highPass_[2];
};

}  // namespace eqcore

#pragma once
// Expression: emotion without loudness for winds and strings (docs/BUILD_BRIEF_0.5.14.md WP10, a speculative
// candidate shipped off by default for blind testing).
//
// The 1-4 kHz band (a second-order band-pass at 2 kHz, Q 0.7: the singing range of sax, trumpet and violin) is
// followed by three power envelopes: 2 ms, 50 ms and 400 ms. Where the band swells above its own 400 ms average its
// gain rises, and where it falls back the gain falls: 0.5 dB per dB of deviation, at most +-0.7 dB (1.4 dB peak to
// peak) at full depth. Swells and decays become larger (vibrato's level ripple only slightly: 50 ms smooths most of it); a 2 s energy match keeps
// the band's average level where it was. Attacks (a 2 ms envelope running above the 50 ms one: drums, picks,
// consonants) close the effect at once and it reopens over about 150 ms, so percussion is never sharpened. Under about
// -80 dBFS nothing happens.
//
// The change is a parallel band gain, x + (gain - 1) * band(x), with the same gain on both channels. depth 0 is a
// bit-exact bypass. No look-ahead; allocation-free and wait-free on the audio thread.
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

class Expression {
 public:
  explicit Expression(double sampleRate);
  void setDepth(double depth);  // any thread; 0..1, non-finite counts as 0
  void reset();
  void process(double* left, double* right, int frames);  // right may be null
  double gainDb() const { return gainDb_.load(std::memory_order_relaxed); }

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
  std::atomic<double> gainDb_{0.0};
  Bq bandL_, bandR_;
  double fast_ = 0, medium_ = 0, slow_ = 0, gain_ = 1.0, open_ = 1.0, inPower_ = 0, outPower_ = 0;
  double aFast_ = 0, aMedium_ = 0, aSlow_ = 0, aGain_ = 0, aGainAttack_ = 0, aOpen_ = 0, aMatch_ = 0;
};

}  // namespace eqcore

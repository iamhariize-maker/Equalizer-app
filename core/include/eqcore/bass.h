#pragma once
// Bass character processor: "punch" (tight, precise attacks) <-> "sustain"
// (long, blooming bass). Level and focus are plain EQ; this is the part EQ
// can't do — it shapes the bass *envelope* over time.
//
// The bass band is split off subtractively (low = LR4 low-pass of x,
// rest = x - low), so with character 0 the output is bit-for-bit x.
// A transient shaper then compares a fast and a slow envelope of the bass:
//   gain = (fast / slow) ^ k,   k > 0 punch, k < 0 sustain
// Attacks (fast > slow) get louder with punch and softer with sustain; the
// decaying tail does the opposite. Gain is clamped to +-12 dB and smoothed.
#include <vector>

namespace eqcore {

class BassShaper {
 public:
  BassShaper(double sampleRate, int channels);

  // character in [-1, 1]: -1 = max sustain, 0 = off, +1 = max punch.
  void setCharacter(double character);
  // Upper edge of the bass band (LR4 crossover), 60..250 Hz.
  void setCrossoverHz(double hz);

  double character() const { return character_; }

  // In-place, one channel's samples. Allocation-free.
  void process(int channel, double* data, int frames);
  void reset();

 private:
  struct Section { double b0, b1, b2, a1, a2; };
  struct ChannelState {
    double z[2][2] = {{0, 0}, {0, 0}};  // two cascaded TDF2 low-pass sections
    double fast = 1e-9, slow = 1e-9, gain = 1.0;
  };
  void design();

  double fs_;
  double character_ = 0.0;
  double crossoverHz_ = 120.0;
  Section lp_{};
  double aFast_, rFast_, aSlow_, rSlow_, gainSmooth_;
  std::vector<ChannelState> ch_;
};

}  // namespace eqcore

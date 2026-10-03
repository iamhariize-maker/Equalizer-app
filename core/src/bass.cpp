#include "eqcore/bass.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
}  // namespace

BassShaper::BassShaper(double sampleRate, int channels) : fs_(sampleRate), ch_(std::max(1, channels)) {
  // Envelope timings tuned for kick/bass-note transients.
  aFast_ = coeff(1.0, fs_);
  rFast_ = coeff(40.0, fs_);
  aSlow_ = coeff(60.0, fs_);
  rSlow_ = coeff(400.0, fs_);
  gainSmooth_ = coeff(3.0, fs_);
  design();
}

void BassShaper::setCharacter(double c) { character_ = std::clamp(c, -1.0, 1.0); }

void BassShaper::setCrossoverHz(double hz) {
  crossoverHz_ = std::clamp(hz, 60.0, 250.0);
  design();
}

void BassShaper::design() {
  // Butterworth low-pass section; two in cascade = Linkwitz-Riley 4th order.
  const double w0 = 2.0 * kPi * crossoverHz_ / fs_;
  const double cw = std::cos(w0), alpha = std::sin(w0) / (2.0 * 0.7071067811865476);
  const double a0 = 1.0 + alpha;
  lp_ = {(1.0 - cw) / 2.0 / a0, (1.0 - cw) / a0, (1.0 - cw) / 2.0 / a0, -2.0 * cw / a0, (1.0 - alpha) / a0};
}

void BassShaper::reset() {
  for (auto& c : ch_) c = ChannelState{};
}

void BassShaper::process(int channel, double* data, int frames) {
  if (channel < 0 || channel >= static_cast<int>(ch_.size())) return;
  ChannelState& s = ch_[channel];
  // Exponent. The tail's fast/slow ratio is numerically smaller than an
  // attack's, so sustain needs a larger exponent to feel as strong as punch.
  const double k = character_ > 0 ? character_ * 1.5 : character_ * 2.6;
  const bool active = std::fabs(k) > 1e-6;
  constexpr double kMinGain = 0.2511886432;  // -12 dB
  constexpr double kMaxGain = 3.981071706;   // +12 dB
  for (int i = 0; i < frames; ++i) {
    const double x = data[i];
    // LR4 low band (always run so the filter state stays warm when toggled).
    double v = x;
    for (int st = 0; st < 2; ++st) {
      const double y = lp_.b0 * v + s.z[st][0];
      s.z[st][0] = lp_.b1 * v - lp_.a1 * y + s.z[st][1];
      s.z[st][1] = lp_.b2 * v - lp_.a2 * y;
      v = y;
    }
    const double low = v;
    const double a = std::fabs(low) + 1e-12;
    s.fast = a > s.fast ? aFast_ * s.fast + (1 - aFast_) * a : rFast_ * s.fast + (1 - rFast_) * a;
    s.slow = a > s.slow ? aSlow_ * s.slow + (1 - aSlow_) * a : rSlow_ * s.slow + (1 - rSlow_) * a;
    double target = 1.0;
    if (active) target = std::clamp(std::pow(s.fast / s.slow, k), kMinGain, kMaxGain);
    s.gain = gainSmooth_ * s.gain + (1 - gainSmooth_) * target;
    // x - low + gain*low: exact identity when gain == 1.
    data[i] = x + (s.gain - 1.0) * low;
  }
}

}  // namespace eqcore

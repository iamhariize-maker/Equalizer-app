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
  // A first-order complementary split has Hlow + Hhigh = 1. With
  // positive bass gain G, |G*Hlow + Hhigh| has no crossover notch.
  // The previous LR4 low-pass mixed with dry audio cancelled near crossover.
  const double k = std::tan(kPi * crossoverHz_ / fs_);
  const double b = k / (1.0 + k);
  lp_ = {b, b, (k - 1.0) / (k + 1.0)};
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
    // Keep the detector/filter warm even with the character control off.
    const double low = lp_.b0 * x + s.lowState;
    s.lowState = lp_.b1 * x - lp_.a1 * low;
    const double a = std::fabs(low) + 1e-12;
    s.fast = a > s.fast ? aFast_ * s.fast + (1 - aFast_) * a : rFast_ * s.fast + (1 - rFast_) * a;
    s.slow = a > s.slow ? aSlow_ * s.slow + (1 - aSlow_) * a : rSlow_ * s.slow + (1 - rSlow_) * a;
    double target = 1.0;
    if (active) target = std::clamp(std::pow(s.fast / s.slow, k), kMinGain, kMaxGain);
    s.gain = active ? gainSmooth_ * s.gain + (1 - gainSmooth_) * target : 1.0;
    // x - low + gain*low: exact identity when gain == 1.
    data[i] = x + (s.gain - 1.0) * low;
  }
}

}  // namespace eqcore

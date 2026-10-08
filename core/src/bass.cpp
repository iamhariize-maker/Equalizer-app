#include "eqcore/bass.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
}  // namespace

BassShaper::BassShaper(double sampleRate, int channels) : fs_(sampleRate), ch_(std::max(1, channels)) {
  designTiming();
  design();
}

void BassShaper::designTiming() {
  // Envelope timings tuned for kick/bass-note transients. Resolve stretches the fast
  // release (a 30 Hz note has a 16 ms rectified half-cycle) and the gain smoothing so
  // neither follows individual cycles of a sustained note.
  aFast_ = coeff(1.0, fs_);
  rFast_ = coeff(40.0 + 30.0 * resolve_, fs_);
  aSlow_ = coeff(60.0, fs_);
  rSlow_ = coeff(400.0, fs_);
  gainSmooth_ = coeff(3.0 + 12.0 * resolve_, fs_);
}

void BassShaper::setResolve(double r) {
  r = std::isfinite(r) ? std::clamp(r, 0.0, 1.0) : 0.0;
  if (r == resolve_) return;
  resolve_ = r;
  designTiming();
}

void BassShaper::setCharacter(double c) {
  c=std::isfinite(c)?std::clamp(c,-1.,1.):0.;
  if(c==0 && character_!=0)for(auto& state:ch_)state.releaseRemaining=std::max(1,static_cast<int>(fs_*.010));
  character_=c;
}

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

double BassShaper::exponent() const {
  // The tail's fast/slow ratio is numerically smaller than an attack's, so sustain needs a
  // larger exponent. Resolve's slower envelopes compress the ratio; the extra exponent lets the
  // tightened caps (1.5 / 0.75 dB) still be reached instead of silently disabling Feel.
  return (character_ > 0 ? character_ * 1.5 : character_ * 2.6) * (1.0 + 2.0 * resolve_);
}

double BassShaper::gainFor(ChannelState& s, double a, double k, bool active) {
  constexpr double kMinGain = 0.2511886432;  // -12 dB
  constexpr double kMaxGain = 3.981071706;   // +12 dB
  s.fast = a > s.fast ? aFast_ * s.fast + (1 - aFast_) * a : rFast_ * s.fast + (1 - rFast_) * a;
  s.slow = a > s.slow ? aSlow_ * s.slow + (1 - aSlow_) * a : rSlow_ * s.slow + (1 - rSlow_) * a;
  double target = 1.0;
  if (active) {
    const double ratio = s.fast / s.slow;
    target = std::clamp(std::pow(ratio, k), kMinGain, kMaxGain);
    if (resolve_ > 0) {
      // Onset (fast above slow) and body/tail get separate dB caps that tighten with Resolve.
      const double capDb = (1 - resolve_) * 12.0 + resolve_ * (ratio > 1.0 ? 1.5 : 0.75);
      const double cap = std::pow(10.0, capDb / 20.0);
      target = std::clamp(target, 1.0 / cap, cap);
    }
  }
  if (active) { s.gain = gainSmooth_ * s.gain + (1 - gainSmooth_) * target; s.releaseRemaining = 0; }
  else if (s.releaseRemaining > 0) { s.gain += (1. - s.gain) / s.releaseRemaining; --s.releaseRemaining; }
  else s.gain = 1.;
  return s.gain;
}

void BassShaper::process(int channel, double* data, int frames) {
  if (channel < 0 || channel >= static_cast<int>(ch_.size())) return;
  ChannelState& s = ch_[channel];
  // Exponent. The tail's fast/slow ratio is numerically smaller than an
  // attack's, so sustain needs a larger exponent to feel as strong as punch.
  const double k = exponent();
  const bool active = std::fabs(k) > 1e-6;
  for (int i = 0; i < frames; ++i) {
    const double x = data[i];
    // Keep the detector/filter warm even with the character control off.
    const double low = lp_.b0 * x + s.lowState;
    s.lowState = lp_.b1 * x - lp_.a1 * low;
    const double g = gainFor(s, std::fabs(low) + 1e-12, k, active);
    // x - low + gain*low: exact identity when gain == 1.
    data[i] = x + (g - 1.0) * low;
  }
}

void BassShaper::processLinked(double* left, double* right, int frames) {
  if (ch_.size() < 2) { process(0, left, frames); return; }
  ChannelState& a = ch_[0];
  ChannelState& b = ch_[1];  // owns the right channel's filter state only
  const double k = exponent();
  const bool active = std::fabs(k) > 1e-6;
  for (int i = 0; i < frames; ++i) {
    const double xl = left[i], xr = right[i];
    const double lowL = lp_.b0 * xl + a.lowState;
    a.lowState = lp_.b1 * xl - lp_.a1 * lowL;
    const double lowR = lp_.b0 * xr + b.lowState;
    b.lowState = lp_.b1 * xr - lp_.a1 * lowR;
    // One detector on the louder channel keeps the pair's balance fixed.
    const double g = gainFor(a, std::max(std::fabs(lowL), std::fabs(lowR)) + 1e-12, k, active);
    left[i] = xl + (g - 1.0) * lowL;
    right[i] = xr + (g - 1.0) * lowR;
  }
}

}  // namespace eqcore

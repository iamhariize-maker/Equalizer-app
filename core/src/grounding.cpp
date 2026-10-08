#include "eqcore/grounding.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {
constexpr double kButterQ1 = 0.5411961001461970, kButterQ2 = 1.3065629648763766;
constexpr double kCrestThresholdDb = 5.0;  // HF spike must exceed its own slow level by this
constexpr double kRatioSlope = 0.5;        // dB reduced per dB of excess (soft 2:1 beyond the threshold)
constexpr double kDrive = 2.0;             // saturation drive on the band (peak ~ 0.5 = -6 dBFS band level)
constexpr double kOddWeight = 1.0, kEvenWeight = 0.08;
constexpr double kActivePower = 1e-8;      // below ~ -80 dBFS rms nothing is restrained
double coef(double seconds, double fs) { return std::exp(-1.0 / (seconds * fs)); }
}  // namespace

Grounding::Grounding(double fs)
    : fs_(fs),
      mixStep_(1.0 / std::max(1.0, 0.020 * fs)),
      aFast_(coef(0.0003, fs)),
      aSlow_(coef(0.040, fs)),
      aAttack_(coef(0.0005, fs)),
      aRelease_(coef(0.015, fs)),
      aDc_(coef(0.050, fs)) {
  const double hz = std::min(kHfCornerHz, 0.35 * fs);
  for (auto& c : ch_) {
    c.hf[0].setCoeffs(designBiquad({FilterType::LowPass, hz, 0, kButterQ1, true}, fs));
    c.hf[1].setCoeffs(designBiquad({FilterType::LowPass, hz, 0, kButterQ2, true}, fs));
    c.bodyHp.setCoeffs(designBiquad({FilterType::HighPass, 100.0, 0, 0.7071067811865476, true}, fs));
    c.bodyLp.setCoeffs(designBiquad({FilterType::LowPass, 1000.0, 0, 0.7071067811865476, true}, fs));
  }
}

void Grounding::setParams(const GroundingParams& p) {
  auto clean = [](double v) { return std::isfinite(v) ? std::clamp(v, 0.0, 1.0) : 0.0; };
  restraintTarget_.store(clean(p.restraint), std::memory_order_relaxed);
  bodyTarget_.store(clean(p.body), std::memory_order_relaxed);
}

void Grounding::reset() {
  for (auto& c : ch_) {
    for (auto& f : c.hf) f.reset();
    c.bodyHp.reset();
    c.bodyLp.reset();
    c.evenDc = 0;
  }
  fast_ = slow_ = 0;
  gain_ = 1;
  restraintMix_ = bodyMix_ = 0;
  restraintDb_.store(0, std::memory_order_relaxed);
}

void Grounding::process(double* left, double* right, int frames) {
  const double rt = restraintTarget_.load(std::memory_order_relaxed);
  const double bt = bodyTarget_.load(std::memory_order_relaxed);
  if (rt == 0 && bt == 0 && restraintMix_ == 0 && bodyMix_ == 0) {
    // Fully off and settled: exact bypass, forget state so re-enabling is click-free.
    if (gain_ != 1 || fast_ != 0 || slow_ != 0) reset();
    return;
  }
  const int C = right ? 2 : 1;
  double* io[2] = {left, right};
  for (int i = 0; i < frames; ++i) {
    restraintMix_ += std::clamp(rt - restraintMix_, -mixStep_, mixStep_);
    bodyMix_ += std::clamp(bt - bodyMix_, -mixStep_, mixStep_);
    double hf[2] = {0, 0}, warm[2] = {0, 0};
    double power = 0;
    for (int c = 0; c < C; ++c) {
      Chan& k = ch_[c];
      const double x = io[c][i];
      // Complementary split: hf = x - lowpass(x), so a gain on hf is in phase with the dry signal.
      const double h = x - k.hf[1].process(k.hf[0].process(x));
      hf[c] = h;
      power += h * h;
      const double b = k.bodyLp.process(k.bodyHp.process(x));
      // Odd part: saturated band minus linear band (unity small-signal gain).
      const double odd = std::tanh(kDrive * b) / kDrive - b;
      // Even part: b^2 with its mean removed; grows with level squared.
      const double sq = b * b;
      k.evenDc = sq + aDc_ * (k.evenDc - sq);
      const double even = sq - k.evenDc;
      warm[c] = kOddWeight * odd + kEvenWeight * even;
    }
    power /= C;
    fast_ = power + aFast_ * (fast_ - power);
    slow_ = power + aSlow_ * (slow_ - power);
    double wantDb = 0;
    if (slow_ > kActivePower) {
      const double crestDb = 10.0 * std::log10((fast_ + 1e-30) / slow_);
      wantDb = -std::min(kMaxRestraintDb, kRatioSlope * std::max(0.0, crestDb - kCrestThresholdDb));
    }
    const double target = std::pow(10.0, wantDb / 20.0);
    const double a = target < gain_ ? aAttack_ : aRelease_;
    gain_ = target + a * (gain_ - target);
    // Depth scales the reduction in dB, so mix 0 is exactly unity.
    const double g = std::pow(gain_, restraintMix_);
    for (int c = 0; c < C; ++c) io[c][i] += (g - 1.0) * hf[c] + bodyMix_ * warm[c];
  }
  restraintDb_.store(20.0 * std::log10(std::max(1e-30, std::pow(gain_, restraintMix_))), std::memory_order_relaxed);
}

}  // namespace eqcore

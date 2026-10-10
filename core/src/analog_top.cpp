#include "eqcore/analog_top.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {
constexpr double kBandHz = 8500.0, kBandQ = 0.6;
constexpr double kKneeDb = -30.0;   // band RMS, dBFS
constexpr double kSlope = 0.15;     // dB of reduction per dB over the knee
constexpr double kMaxDb = 2.5;      // at full depth
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
}  // namespace

AnalogTop::AnalogTop(double sampleRate) {
  const auto c = designBiquad({FilterType::BandPass, std::min(kBandHz, 0.4 * sampleRate), 0.0, kBandQ, true}, sampleRate);
  bandL_.c = bandR_.c = c;
  aPower_ = coeff(30, sampleRate);
  aGain_ = coeff(10, sampleRate);
}

void AnalogTop::setDepth(double depth) {
  depth_.store(std::isfinite(depth) ? std::clamp(depth, 0.0, 1.0) : 0.0, std::memory_order_relaxed);
}

void AnalogTop::reset() {
  bandL_.z1 = bandL_.z2 = bandR_.z1 = bandR_.z2 = 0.0;
  power_ = 0.0;
  gain_ = 1.0;
  reductionDb_.store(0.0, std::memory_order_relaxed);
}

void AnalogTop::process(double* L, double* R, int frames) {
  const double depth = depth_.load(std::memory_order_relaxed);
  for (int i = 0; i < frames; ++i) {
    const double yl = bandL_.run(L[i]);
    const double yr = R ? bandR_.run(R[i]) : yl;
    const double mid = 0.5 * (yl + yr);
    power_ = aPower_ * power_ + (1.0 - aPower_) * mid * mid;
    double target = 1.0;
    if (depth > 0.0 && power_ > 1e-10) {
      const double levelDb = 10.0 * std::log10(power_);
      const double reduction = depth * std::clamp(kSlope * (levelDb - kKneeDb), 0.0, kMaxDb);
      target = std::pow(10.0, -reduction / 20.0);
    }
    gain_ += (1.0 - aGain_) * (target - gain_);
    if (std::fabs(target - gain_) < 1e-9) gain_ = target;  // settle exactly, so the bypass stays bit-exact
    const double delta = gain_ - 1.0;
    L[i] += delta * yl;
    if (R) R[i] += delta * yr;
  }
  reductionDb_.store(20.0 * std::log10(gain_), std::memory_order_relaxed);
}

}  // namespace eqcore

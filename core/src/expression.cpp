#include "eqcore/expression.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {
constexpr double kBandHz = 2000.0, kBandQ = 0.7;
constexpr double kRatio = 0.5;        // dB of gain per dB of deviation from the 400 ms average
constexpr double kMaxDb = 0.7;        // each way at full depth (1.4 dB peak to peak)
constexpr double kAttackFromDb = 2.0, kAttackSpanDb = 4.0;  // 2 ms over 50 ms power: an attack closes the effect
constexpr double kFloor = 1e-8;       // band power floor, about -80 dBFS
constexpr double kEps = 1e-30;
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
}  // namespace

Expression::Expression(double sampleRate) {
  bandL_.c = bandR_.c = designBiquad({FilterType::BandPass, kBandHz, 0.0, kBandQ, true}, sampleRate);
  aFast_ = coeff(2, sampleRate);
  aOpen_ = coeff(150, sampleRate);
  aMatch_ = coeff(2000, sampleRate);
  aMedium_ = coeff(50, sampleRate);
  aSlow_ = coeff(400, sampleRate);
  aGain_ = coeff(10, sampleRate);
  aGainAttack_ = coeff(0.5, sampleRate);
}

void Expression::setDepth(double depth) {
  depth_.store(std::isfinite(depth) ? std::clamp(depth, 0.0, 1.0) : 0.0, std::memory_order_relaxed);
}

void Expression::reset() {
  bandL_.z1 = bandL_.z2 = bandR_.z1 = bandR_.z2 = 0.0;
  fast_ = medium_ = slow_ = 0.0;
  gain_ = 1.0;
  open_ = 1.0;
  inPower_ = outPower_ = 0.0;
  gainDb_.store(0.0, std::memory_order_relaxed);
}

void Expression::process(double* L, double* R, int frames) {
  const double depth = depth_.load(std::memory_order_relaxed);
  for (int i = 0; i < frames; ++i) {
    const double yl = bandL_.run(L[i]);
    const double yr = R ? bandR_.run(R[i]) : yl;
    const double power = 0.5 * (yl * yl + yr * yr);
    fast_ = aFast_ * fast_ + (1.0 - aFast_) * power;
    medium_ = aMedium_ * medium_ + (1.0 - aMedium_) * power;
    slow_ = aSlow_ * slow_ + (1.0 - aSlow_) * power;
    double target = 1.0;
    bool attack = false;
    if (depth > 0.0 && slow_ > kFloor && medium_ > kFloor) {
      // An attack closes the effect at once; it reopens over about 150 ms, so the ringing after a hit is left alone too.
      const double attackDb = 10.0 * std::log10((fast_ + kEps) / (medium_ + kEps));
      const double wanted = 1.0 - std::clamp((attackDb - kAttackFromDb) / kAttackSpanDb, 0.0, 1.0);
      attack = wanted < open_;
      open_ = attack ? wanted : aOpen_ * open_ + (1.0 - aOpen_) * wanted;
      const double deviationDb = 10.0 * std::log10(medium_ / slow_);
      const double gainDb = depth * open_ * std::clamp(kRatio * deviationDb, -kMaxDb, kMaxDb);
      // Loud moments weigh more in energy, so plain expansion would raise the level; a 2 s energy match keeps it.
      const double match = outPower_ > kFloor && inPower_ > kFloor ? std::clamp(std::sqrt(inPower_ / outPower_), 0.9, 1.1) : 1.0;
      target = std::pow(10.0, gainDb / 20.0) * (1.0 + (match - 1.0) * open_);  // an attack gets neither
    } else {
      open_ = 1.0;
    }
    // At an attack the gain returns to neutral within about half a millisecond, so a hit never inherits the cut of
    // the decay before it; otherwise it moves smoothly (10 ms).
    const double a = attack || open_ < 0.5 ? aGainAttack_ : aGain_;
    gain_ += (1.0 - a) * (target - gain_);
    if (std::fabs(target - gain_) < 1e-9) gain_ = target;  // settle exactly, so the bypass stays bit-exact
    const double delta = gain_ - 1.0;
    inPower_ = aMatch_ * inPower_ + (1.0 - aMatch_) * power;
    outPower_ = aMatch_ * outPower_ + (1.0 - aMatch_) * power * gain_ * gain_;
    L[i] += delta * yl;
    if (R) R[i] += delta * yr;
  }
  gainDb_.store(20.0 * std::log10(gain_), std::memory_order_relaxed);
}

}  // namespace eqcore

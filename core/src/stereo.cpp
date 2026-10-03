#include "eqcore/stereo.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
}  // namespace

StereoTuner::StereoTuner(double sampleRate) : fs_(sampleRate) {
  aBand_ = coeff(1.0, fs_);
  rBand_ = coeff(60.0, fs_);
  aFull_ = coeff(5.0, fs_);
  rFull_ = coeff(150.0, fs_);
  gSmooth_ = coeff(2.0, fs_);
  redesign(p_);
}

void StereoTuner::setParams(const StereoTunerParams& p) {
  // Tiny spin lock: UI-thread writers only; the audio thread uses try-semantics.
  while (pendingLock_.exchange(true, std::memory_order_acquire)) {
  }
  pending_ = p;
  pendingLock_.store(false, std::memory_order_release);
  version_.fetch_add(1, std::memory_order_release);
}

void StereoTuner::redesign(const StereoTunerParams& p) {
  const auto set = [&](Bq& b, FilterType t, double f, double g, double q) {
    b.c = designBiquad({t, f, g, q, true}, fs_);
  };
  set(warmBell_, FilterType::Peak, 220.0, 3.0 * p.warmth, 0.9);
  set(warmShelf_, FilterType::HighShelf, 8000.0, -2.0 * p.warmth, 0.7);
  set(intimacyBell_, FilterType::Peak, 1200.0, 2.5 * p.intimacy, 0.6);
  set(harshBand_, FilterType::BandPass, 3800.0, 0.0, 0.9);
  for (auto& lp : sideLp_) set(lp, FilterType::LowPass, 180.0, 0.0, 0.7071067811865476);
  for (auto& hp : sideHp_) set(hp, FilterType::HighPass, 180.0, 0.0, 0.7071067811865476);
  set(bodyBell_, FilterType::Peak, 500.0, 1.5 * p.instruments, 1.0);
  set(presenceBell_, FilterType::Peak, 3000.0, 4.0 * p.instruments, 0.7);
  set(airShelf_, FilterType::HighShelf, 10000.0, 3.0 * p.instruments, 0.7);
  spaceGain_ = std::pow(10.0, 6.0 * std::clamp(p.space, -1.0, 1.0) / 20.0);
}

void StereoTuner::reset() {
  for (Bq* b : {&warmBell_, &warmShelf_, &intimacyBell_, &harshBand_, &sideLp_[0], &sideLp_[1], &sideHp_[0], &sideHp_[1], &bodyBell_,
                &presenceBell_, &airShelf_})
    b->z1 = b->z2 = 0;
  envBand_ = envFull_ = 1e-9;
  deharshGain_ = 1.0;
}

void StereoTuner::process(double* L, double* R, int frames) {
  const int v = version_.load(std::memory_order_acquire);
  if (v != appliedVersion_ && !pendingLock_.exchange(true, std::memory_order_acquire)) {
    p_ = pending_;
    pendingLock_.store(false, std::memory_order_release);
    redesign(p_);
    appliedVersion_ = v;
  }
  if (p_.isOff()) {
    lastDeharshDb_ = 0;
    return;  // bit-exact passthrough
  }
  const bool vocal = p_.intimacy != 0 || p_.warmth != 0 || p_.smoothness != 0;
  const bool side = p_.space != 0 || p_.instruments != 0;
  // De-harsh: band level relative to the whole voice, above a threshold that
  // drops as smoothness rises; up to 12 dB of reduction.
  const double thrDb = -4.0 - 8.0 * p_.smoothness;
  const double maxRedDb = 12.0 * p_.smoothness;
  double minGain = 1.0;

  for (int i = 0; i < frames; ++i) {
    double m = 0.5 * (L[i] + R[i]);
    double s = 0.5 * (L[i] - R[i]);
    if (vocal) {
      m = intimacyBell_.run(warmShelf_.run(warmBell_.run(m)));
      if (p_.smoothness > 0) {
        const double band = harshBand_.run(m);
        const double ab = std::fabs(band) + 1e-12, af = std::fabs(m) + 1e-12;
        envBand_ = ab > envBand_ ? aBand_ * envBand_ + (1 - aBand_) * ab : rBand_ * envBand_ + (1 - rBand_) * ab;
        envFull_ = af > envFull_ ? aFull_ * envFull_ + (1 - aFull_) * af : rFull_ * envFull_ + (1 - rFull_) * af;
        const double excess = 20.0 * std::log10(envBand_ / envFull_) - thrDb;
        const double redDb = std::clamp(excess * 0.8, 0.0, maxRedDb);
        const double target = std::pow(10.0, -redDb / 20.0);
        deharshGain_ = gSmooth_ * deharshGain_ + (1 - gSmooth_) * target;
        m += (deharshGain_ - 1.0) * band;
        minGain = std::min(minGain, deharshGain_);
      }
    }
    if (side) {
      // Linkwitz-Riley crossover at 180 Hz: low + high sum to a flat allpass,
      // so side bass keeps its level while only the upper band is shaped.
      const double low = sideLp_[1].run(sideLp_[0].run(s));
      double high = sideHp_[1].run(sideHp_[0].run(s));
      high = airShelf_.run(presenceBell_.run(bodyBell_.run(high)));
      s = low + spaceGain_ * high;
    }
    L[i] = m + s;
    R[i] = m - s;
  }
  lastDeharshDb_ = 20.0 * std::log10(minGain);
}

}  // namespace eqcore

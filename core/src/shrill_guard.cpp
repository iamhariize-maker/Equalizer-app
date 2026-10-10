#include "eqcore/shrill_guard.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {

// Presence: fourth-order band-pass at 4 kHz. Sizzle: fourth-order band-pass at 8 kHz. Both have zero phase at
// their centre, so the parallel band subtraction is an exact gain there.
constexpr double kCentreHz[ShrillGuard::kBands] = {4000.0, 8000.0};
constexpr double kQ[ShrillGuard::kBands] = {1.0, 1.4};
// Band level relative to the whole mix (amplitude dB) above which a band counts as shrill. Checked on pure tones
// (filter responses): a 1 kHz tone sits about -24 dB in the presence band and is never reduced; a 4 kHz tone sits
// about -15 dB in the sizzle band and an 8 kHz tone about -10 dB in the presence band, both under their thresholds,
// so no band reduces another band's content.
constexpr double kThresholdDb[ShrillGuard::kBands] = {-9.0, -12.0};
constexpr double kMaxReductionDb = 2.0;  // per band at full depth
// Power floor, about -100 dBFS. Below it neither the mix nor a band has anything to judge, so no reduction is applied.
// Without the floor, long digital silence (every envelope decaying to zero) would read as "shrill" and pre-reduce the
// next sound.
constexpr double kFloorPower = 1e-10;
constexpr double kEps = 1e-30;

double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }

}  // namespace

ShrillGuard::ShrillGuard(double sampleRate) {
  for (size_t b = 0; b < kBands; ++b) {
    const auto c = designBiquad({FilterType::BandPass, kCentreHz[b], 0.0, kQ[b], true}, sampleRate);
    for (int s = 0; s < 2; ++s) {
      bands_[b].passL[s].c = c;
      bands_[b].passR[s].c = c;
    }
  }
  // Symmetric one-pole smoothing of power: a steady tone gives a flat envelope (no rectifier ripple), and an
  // attack shows up as the fast envelope running ahead of the slow one.
  aMedium_ = coeff(2, sampleRate);
  aSlow_ = coeff(40, sampleRate);
  aGain_ = coeff(10, sampleRate);
}

void ShrillGuard::setDepth(double depth) {
  depth_.store(std::isfinite(depth) ? std::clamp(depth, 0.0, 1.0) : 0.0, std::memory_order_relaxed);
}

void ShrillGuard::reset() {
  for (auto& band : bands_) {
    for (int s = 0; s < 2; ++s) {
      band.passL[s].z1 = band.passL[s].z2 = 0.0;
      band.passR[s].z1 = band.passR[s].z2 = 0.0;
    }
    band.medium = band.slow = 0.0;
    band.gain = 1.0;
  }
  fullSlow_ = 0.0;
  for (auto& r : reductionDb_) r.store(0.0, std::memory_order_relaxed);
}

std::array<double, ShrillGuard::kBands> ShrillGuard::reductionsDb() const {
  std::array<double, kBands> out{};
  for (size_t b = 0; b < kBands; ++b) out[b] = reductionDb_[b].load(std::memory_order_relaxed);
  return out;
}

void ShrillGuard::process(double* L, double* R, int frames) {
  const double depth = depth_.load(std::memory_order_relaxed);
  for (int i = 0; i < frames; ++i) {
    const double l = L[i];
    const double r = R ? R[i] : l;
    const double mid = 0.5 * (l + r);
    // Mix power: the reference that decides whether a band is shrill relative to the whole sound.
    fullSlow_ = aSlow_ * fullSlow_ + (1.0 - aSlow_) * mid * mid;
    std::array<double, kBands> yl{}, yr{};
    for (size_t b = 0; b < kBands; ++b) {
      Band& band = bands_[b];
      double xl = l, xr = r;
      for (int s = 0; s < 2; ++s) {
        xl = band.passL[s].run(xl);
        xr = R ? band.passR[s].run(xr) : xl;
      }
      yl[b] = xl;
      yr[b] = xr;
      const double bandMid = 0.5 * (yl[b] + yr[b]);
      const double power = bandMid * bandMid;
      band.medium = aMedium_ * band.medium + (1.0 - aMedium_) * power;
      band.slow = aSlow_ * band.slow + (1.0 - aSlow_) * power;
      // Excess over the band's threshold (amplitude dB: 10 log10 of a power ratio), and the sustain gate: closed
      // while the fast (2 ms) envelope runs well above the slow (40 ms) one, i.e. during an attack.
      const bool judged = fullSlow_ > kFloorPower && band.slow > kFloorPower;
      const double shrillDb = 10.0 * std::log10((band.slow + kEps) / (fullSlow_ + kEps)) - kThresholdDb[b];
      const double sustainDb = 10.0 * std::log10((band.slow + kEps) / (band.medium + kEps));
      const double sustain = std::clamp((sustainDb + 12.0) / 12.0, 0.0, 1.0);
      const double reductionDb = judged ? depth * std::clamp(0.5 * shrillDb, 0.0, kMaxReductionDb) * sustain : 0.0;
      const double target = std::pow(10.0, -reductionDb / 20.0);
      band.gain += (1.0 - aGain_) * (target - band.gain);
      if (std::fabs(target - band.gain) < 1e-9) band.gain = target;  // settle exactly, so the bypass stays bit-exact
      reductionDb_[b].store(20.0 * std::log10(band.gain), std::memory_order_relaxed);
    }
    // Both channels receive the same band gain, so the stereo image cannot move.
    double outL = l;
    double outR = r;
    for (size_t b = 0; b < kBands; ++b) {
      const double delta = bands_[b].gain - 1.0;
      outL += delta * yl[b];
      outR += delta * yr[b];
    }
    L[i] = outL;
    if (R) R[i] = outR;
  }
}

}  // namespace eqcore

#include "eqcore/shrill_guard.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace eqcore {
namespace {

// Presence: fourth-order band-pass at 4 kHz. Sizzle: fourth-order band-pass at 8 kHz. Both have zero phase at
// their centre, so the parallel band subtraction is an exact gain there. The presence band is broad (Q 0.6) so a
// cluster of guitar partials across 3.5-5 kHz is reduced evenly (with Q 1 a 4.8 kHz partial got only 60% of the cut).
constexpr double kCentreHz[ShrillGuard::kBands] = {4000.0, 8000.0};
constexpr double kQ[ShrillGuard::kBands] = {0.6, 1.4};
// Residual over the mix's tilt (dB) above which a band counts as shrill. The presence value matches the Svaresa
// harshness rule (SM-HARSH-1 starts smoothing at 1.5 dB); sizzle needs a little more, as cymbals naturally carry it.
constexpr double kThresholdDb[ShrillGuard::kBands] = {1.5, 2.0};
constexpr double kSlope = 0.5;           // dB of reduction per dB of excess
constexpr double kMaxReductionDb = 2.0;  // per band at full depth
// Voice protection: presence reduction scaled down by up to kVoiceProtection as centre dominance (1-4 kHz mid over
// side) rises from kCentreFromDb over kCentreSpanDb.
constexpr double kVoiceProtection = 0.75, kCentreFromDb = 10.0, kCentreSpanDb = 10.0;
// Band power floor, about -100 dBFS. Below it a band has nothing to reduce, so nothing is applied: long digital
// silence never leaves a reduction waiting for the next sound.
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
  for (auto& e : excess_) e.store(std::numeric_limits<double>::quiet_NaN(), std::memory_order_relaxed);
}

void ShrillGuard::setPresenceCap(double db) {
  presenceCap_.store(std::isfinite(db) ? std::clamp(db, 0.0, kMaxReductionDb) : kMaxReductionDb, std::memory_order_relaxed);
}

void ShrillGuard::setExcess(double presenceDb, double sizzleDb, double centreDb) {
  excess_[0].store(presenceDb, std::memory_order_relaxed);
  excess_[1].store(sizzleDb, std::memory_order_relaxed);
  const double c = std::isfinite(centreDb) ? centreDb : 0.0;
  presenceScale_.store(1.0 - kVoiceProtection * std::clamp((c - kCentreFromDb) / kCentreSpanDb, 0.0, 1.0), std::memory_order_relaxed);
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
  for (auto& r : reductionDb_) r.store(0.0, std::memory_order_relaxed);
}

std::array<double, ShrillGuard::kBands> ShrillGuard::reductionsDb() const {
  std::array<double, kBands> out{};
  for (size_t b = 0; b < kBands; ++b) out[b] = reductionDb_[b].load(std::memory_order_relaxed);
  return out;
}

void ShrillGuard::process(double* L, double* R, int frames) {
  const double depth = depth_.load(std::memory_order_relaxed);
  // Per block: how far each band's residual is over its threshold (or unknown).
  std::array<double, kBands> over{}, scale{}, cap{};
  for (size_t b = 0; b < kBands; ++b) {
    const double e = excess_[b].load(std::memory_order_relaxed);
    over[b] = std::isfinite(e) ? e - kThresholdDb[b] : -1.0;
    scale[b] = b == 0 ? presenceScale_.load(std::memory_order_relaxed) : 1.0;
    cap[b] = b == 0 ? presenceCap_.load(std::memory_order_relaxed) : kMaxReductionDb;
  }
  for (int i = 0; i < frames; ++i) {
    const double l = L[i];
    const double r = R ? R[i] : l;
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
      // The sustain gate: closed while the fast (2 ms) envelope runs well above the slow (40 ms) one, i.e. during
      // an attack, so picks and hi-hat clicks pass; open once the energy holds.
      const bool judged = over[b] > 0.0 && band.slow > kFloorPower;
      const double sustainDb = 10.0 * std::log10((band.slow + kEps) / (band.medium + kEps));
      const double sustain = std::clamp((sustainDb + 12.0) / 12.0, 0.0, 1.0);
      const double reductionDb = judged ? depth * scale[b] * std::clamp(kSlope * over[b], 0.0, cap[b]) * sustain : 0.0;
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

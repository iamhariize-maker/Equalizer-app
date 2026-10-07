#pragma once
// Original design reference for the 0.5.7 handoff. Not linked into the app.
// These are decision kernels, not a complete mastering processor.
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>

namespace svan_reference {
inline double unit(double x) { return std::isfinite(x) ? std::clamp(x, 0., 1.) : 0.; }
inline double amplitude(double db) { return std::pow(10., db / 20.); }

enum class Owner { Off, Manual, Auto };
struct Control {
  Owner owner = Owner::Off;
  double manual = 0, automaticCeiling = .7;
};
struct Evidence {
  bool nativeRoute = false, sameEpoch = false;
  double confidence = 0, ageSeconds = 1e9;
};
inline double resolve(const Control& c, bool autoMaster, double automatic, const Evidence& e) {
  if (!e.nativeRoute || c.owner == Owner::Off) return 0;
  if (c.owner == Owner::Manual) return unit(c.manual);
  if (!autoMaster || !e.sameEpoch || !std::isfinite(e.ageSeconds) || e.ageSeconds < 0 ||
      e.ageSeconds > .5 || unit(e.confidence) < .8) return 0;
  return std::min(unit(automatic), unit(c.automaticCeiling));
}

// Scale a proposed side-only spectral delta D so E|S+aD|^2 <= limit.
// q = Re E[S*conj(D)]. Moments MUST come from the same aligned band/window.
// The quadratic is convex. Starting inside the permitted ball gives one
// nonnegative exit root; no binary search or per-sample clipping is needed.
inline double sideScale(double pMid, double pSide, double pDelta, double q,
                        double sideToMidLimit = .5, double maxLiftDb = 4.) {
  if (!(std::isfinite(pMid) && std::isfinite(pSide) && std::isfinite(pDelta) &&
        std::isfinite(q) && std::isfinite(sideToMidLimit) && std::isfinite(maxLiftDb)) ||
      pMid <= 1e-20 || pSide < 0 || pDelta <= 1e-30 || sideToMidLimit <= 0 || maxLiftDb < 0)
    return 0;
  if (std::abs(q) > std::sqrt(pSide * pDelta) * (1. + 1e-9) + 1e-25) return 0;
  const double limit = std::min(sideToMidLimit * pMid, pSide * std::pow(10., maxLiftDb / 10.));
  if (pSide >= limit) return 0; // Already wide: do not add more side energy.
  if (pSide + 2*q + pDelta <= limit) return 1;
  const double slack = limit - pSide;
  const double disc = std::sqrt(q*q + pDelta*slack);
  // Rationalized root avoids cancellation when q is large and positive.
  const double root = q >= 0 ? slack / (disc + q) : (disc - q) / pDelta;
  return unit(root);
}

struct StereoMoments {
  double midPower = 0, sidePower = 0;
  std::complex<double> sideMidCross{}; // E[S * conj(M)]
};
struct Residual {
  std::complex<double> value{};
  double coherence = 1;
  bool eligible = false;
};
inline Residual decorrelatedResidual(std::complex<double> m, std::complex<double> s,
                                     const StereoMoments& p) {
  if (!(std::isfinite(m.real()) && std::isfinite(m.imag()) && std::isfinite(s.real()) &&
        std::isfinite(s.imag()) && std::isfinite(p.midPower) && std::isfinite(p.sidePower) &&
        std::isfinite(p.sideMidCross.real()) && std::isfinite(p.sideMidCross.imag())) ||
      p.midPower <= 1e-16 || p.sidePower <= 1e-16) return {};
  const double crossPower = std::norm(p.sideMidCross);
  const double product = p.midPower*p.sidePower;
  if (crossPower > product*(1.+1e-9)) return {}; // Inconsistent covariance.
  const double coherence = unit(crossPower/product);
  if (coherence >= .98 || p.sidePower >= .5*p.midPower) return {{},coherence,false};
  const auto beta = p.sideMidCross / (p.midPower + 1e-12*(p.midPower+p.sidePower));
  return {s-beta*m,coherence,true};
}

// Requested boosts share one gain budget. Apply only to the eligible residual,
// followed by sideScale, transient/low-band masks, and frame peak constraints.
inline double detailGainDb(double backing, double spatial, double vocalMask,
                           double spaceMask, double confidence, double onsetGuard) {
  const double requested = 4*unit(backing)*unit(vocalMask) + 3*unit(spatial)*unit(spaceMask);
  return std::min(4., requested) * unit(confidence) * (1-unit(onsetGuard));
}

struct BassEvidence {
  bool valid = false, sameEpoch = false;
  double confidence = 0, ageSeconds = 1e9, levelDbfs = -120;
  double onsetAgeMs = 0, sustainedExcessMs = 0, excessDb = 0;
  // Confidence this lane contains an intentional fundamental or harmonic.
  // Unknown pitch/tonality is represented by valid=false, not by zero.
  double harmonicProtection = 1;
};
inline double bassReductionDb(double amount, const BassEvidence& e) {
  if (!(e.valid && e.sameEpoch) || unit(e.confidence) < .8 ||
      !std::isfinite(e.ageSeconds) || e.ageSeconds < 0 || e.ageSeconds > .5 ||
      !std::isfinite(e.levelDbfs) || e.levelDbfs < -60 ||
      !std::isfinite(e.onsetAgeMs) || e.onsetAgeMs < 50 ||
      !std::isfinite(e.sustainedExcessMs) || e.sustainedExcessMs < 150 ||
      !std::isfinite(e.excessDb) || !std::isfinite(e.harmonicProtection)) return 0;
  return unit(amount)*unit(e.confidence)*(1-unit(e.harmonicProtection))*
      std::clamp((e.excessDb-6.)*.5, 0., 1.5);
}

// Shared serial-lane attenuation budget; uses actual smoothed reductions too.
template <std::size_t N>
inline std::array<double,N> reductionBudget(std::array<double,N> db, double maxSumDb) {
  double sum = 0;
  for (auto& x:db) { x=std::isfinite(x)?std::max(0.,x):0.; sum+=x; }
  const double cap=std::isfinite(maxSumDb)?std::max(0.,maxSumDb):0.;
  if(sum>cap && sum>0) for(auto& x:db) x*=cap/sum;
  return db;
}

// Resolve protects the existing Feel envelope; it does not add a second
// transient enhancer. Onset/body are detector states, not user-set delays.
inline double guardedCharacterDb(double requestedDb, double resolveAmount, bool onset) {
  const double requested=std::isfinite(requestedDb)?std::clamp(requestedDb,-12.,12.):0.;
  const double r=unit(resolveAmount);
  const double cap=(1-r)*12+r*(onset?1.5:.75);
  return std::clamp(requested,-cap,cap);
}

struct DbSmoother {
  double value = 0;
  double tick(double target, double fs, double attackMs = 50, double releaseMs = 400) {
    const double ms = target > value ? attackMs : releaseMs;
    const double a = std::exp(-1./(.001*ms*fs));
    return value = target + a*(value-target);
  }
};

// Maintain quality's INTERNAL design-rate family as base rate rises. This is
// not a high-resolution authenticity detector or a route-negotiation function.
inline int oversamplingFor(int rate, int qualityIndex) {
  const int family = (rate==44100 || rate==88200 || rate==176400) ? 44100 : 48000;
  const int q=std::clamp(qualityIndex,0,3);
  const int target=family*(1<<q);
  int factor=1;
  while(factor<8 && rate*factor<target) factor*=2;
  return factor;
}
} // namespace svan_reference

#include "eqcore/parametric_eq.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
// Added to the input of every section so decaying filter state never reaches
// the denormal range (which is very slow on many CPUs). -500 dBFS: inaudible.
constexpr double kDenormGuard = 1e-25;
}  // namespace

ParametricEq::ParametricEq(int channels, double sampleRate)
    : channels_(channels),
      fs_(sampleRate),
      pending_(channels),
      dirty_(channels),
      rt_(channels) {
  for (auto& d : dirty_) d.store(false);
  // Reserve once so publishing a new band list never allocates on the audio thread.
  for (auto& rt : rt_) {
    rt.coeffs.reserve(kMaxBandsPerChannel);
    rt.z.reserve(kMaxBandsPerChannel);
  }
}

void ParametricEq::setBands(int channel, const std::vector<BandParams>& bands) {
  if (channel < 0 || channel >= channels_) return;
  std::vector<BiquadCoeffs> coeffs;
  coeffs.reserve(std::min<size_t>(bands.size(), kMaxBandsPerChannel));
  for (const auto& b : bands) {
    if (static_cast<int>(coeffs.size()) >= kMaxBandsPerChannel) break;
    if (isIdentityBand(b)) continue;  // skipping saves CPU
    coeffs.push_back(designBiquad(b, fs_));
  }
  std::lock_guard<std::mutex> lock(mu_);
  pending_[channel].coeffs = std::move(coeffs);
  dirty_[channel].store(true, std::memory_order_release);
}

void ParametricEq::process(int channel, double* data, int frames) {
  if (channel < 0 || channel >= channels_) return;
  Runtime& rt = rt_[channel];

  if (dirty_[channel].load(std::memory_order_acquire)) {
    std::unique_lock<std::mutex> lock(mu_, std::try_to_lock);
    if (lock.owns_lock()) {
      // Capacity was reserved in the constructor, so these never allocate.
      rt.coeffs = pending_[channel].coeffs;
      rt.z.resize(rt.coeffs.size(), {0.0, 0.0});
      dirty_[channel].store(false, std::memory_order_release);
    }
  }

  // Section-outer / sample-inner keeps each section's state in registers.
  for (size_t s = 0; s < rt.coeffs.size(); ++s) {
    const BiquadCoeffs c = rt.coeffs[s];
    double z1 = rt.z[s][0], z2 = rt.z[s][1];
    for (int i = 0; i < frames; ++i) {
      const double x = data[i] + kDenormGuard;
      const double y = c.b0 * x + z1;
      z1 = c.b1 * x - c.a1 * y + z2;
      z2 = c.b2 * x - c.a2 * y;
      data[i] = y;
    }
    rt.z[s][0] = z1;
    rt.z[s][1] = z2;
  }
}

double ParametricEq::responseDb(int channel, double freqHz) const {
  if (channel < 0 || channel >= channels_) return 0.0;
  std::lock_guard<std::mutex> lock(mu_);
  std::complex<double> h(1.0, 0.0);
  for (const auto& c : pending_[channel].coeffs) h *= responseAt(c, freqHz, fs_);
  return 20.0 * std::log10(std::max(std::abs(h), 1e-300));
}

double ParametricEq::peakGainDb(int channel, double minHz, double maxHz, int points) const {
  double peak = 0.0;
  const double lo = std::log(minHz), hi = std::log(maxHz);
  for (int i = 0; i < points; ++i) {
    const double f = std::exp(lo + (hi - lo) * i / (points - 1));
    peak = std::max(peak, responseDb(channel, f));
  }
  return peak;
}

void ParametricEq::reset() {
  for (auto& rt : rt_)
    for (auto& z : rt.z) z = {0.0, 0.0};
}

}  // namespace eqcore

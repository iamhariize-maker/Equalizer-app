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
      fadeFrames_(std::max(1, static_cast<int>(std::round(sampleRate * 0.010)))),
      pending_(channels),
      dirty_(channels),
      rt_(channels) {
  for (auto& d : dirty_) d.store(false);
  // Reserve once so publishing a new band list never allocates on the audio thread.
  for (auto& rt : rt_) for(auto& bank:rt.banks) {
    bank.coeffs.reserve(kMaxBandsPerChannel);
    bank.params.reserve(kMaxBandsPerChannel);
    bank.z.reserve(kMaxBandsPerChannel);
  }
}

void ParametricEq::setBands(int channel, const std::vector<BandParams>& bands) {
  if (channel < 0 || channel >= channels_) return;
  std::vector<BiquadCoeffs> coeffs;
  coeffs.reserve(std::min<size_t>(bands.size(), kMaxBandsPerChannel));
  std::vector<BandParams> params;
  params.reserve(std::min<size_t>(bands.size(), kMaxBandsPerChannel));
  for (const auto& b : bands) {
    if (static_cast<int>(coeffs.size()) >= kMaxBandsPerChannel) break;
    // Keep zero/disabled slots so a gain crossing zero cannot shift other states.
    coeffs.push_back(isIdentityBand(b) ? BiquadCoeffs{} : designBiquad(b, fs_));
    params.push_back(b);
  }
  std::lock_guard<std::mutex> lock(mu_);
  pending_[channel].coeffs = std::move(coeffs);
  pending_[channel].params = std::move(params);
  dirty_[channel].store(true, std::memory_order_release);
}

void ParametricEq::process(int channel, double* data, int frames) {
  if (channel < 0 || channel >= channels_) return;
  Runtime& rt = rt_[channel];

  if (rt.fadeRemaining == 0 && dirty_[channel].load(std::memory_order_acquire)) {
    std::unique_lock<std::mutex> lock(mu_, std::try_to_lock);
    if (lock.owns_lock()) {
      auto& old=rt.banks[rt.active];
      auto& next=rt.banks[1-rt.active];
      next.coeffs=pending_[channel].coeffs;
      next.params=pending_[channel].params;
      next.z.assign(next.coeffs.size(),{0.0,0.0});
      // Retain histories only for the same filter identity, never an unrelated
      // filter whose index changed when a band was inserted/removed.
      const auto same=[](const BandParams& a,const BandParams& b){
        return a.type==b.type && a.freqHz==b.freqHz && a.q==b.q;
      };
      for(size_t i=0;i<next.params.size();++i) {
        if(isIdentityBand(next.params[i])) continue;
        if(i<old.params.size() && same(next.params[i],old.params[i])) next.z[i]=old.z[i];
        else for(size_t j=0;j<old.params.size();++j) if(same(next.params[i],old.params[j])){next.z[i]=old.z[j];break;}
      }
      const auto equal=[](const BiquadCoeffs& a,const BiquadCoeffs& b){
        return a.b0==b.b0 && a.b1==b.b1 && a.b2==b.b2 && a.a1==b.a1 && a.a2==b.a2;
      };
      if(!rt.initialized || (old.coeffs.size()==next.coeffs.size() && std::equal(old.coeffs.begin(),old.coeffs.end(),next.coeffs.begin(),equal)))
        rt.active=1-rt.active;
      else rt.fadeRemaining=fadeFrames_;
      dirty_[channel].store(false, std::memory_order_release);
    }
  }

  rt.initialized=true;
  for(int start=0;start<frames;start+=512) {
    const int n=std::min(512,frames-start);
    if(rt.fadeRemaining==0) { run(rt.banks[rt.active],data+start,n);continue; }
    std::copy_n(data+start,n,rt.oldOutput.begin());
    std::copy_n(data+start,n,rt.newOutput.begin());
    run(rt.banks[rt.active],rt.oldOutput.data(),n);
    run(rt.banks[1-rt.active],rt.newOutput.data(),n);
    for(int i=0;i<n;++i) {
      const double t=1.0-static_cast<double>(rt.fadeRemaining)/fadeFrames_;
      data[start+i]=rt.oldOutput[i]+t*(rt.newOutput[i]-rt.oldOutput[i]);
      if(rt.fadeRemaining>0)--rt.fadeRemaining;
    }
    if(rt.fadeRemaining==0)rt.active=1-rt.active;
  }
}

void ParametricEq::run(Bank& bank,double* data,int frames) {
  // Stable identity slots cost no sample work. Changed chains run in parallel
  // only during a transition; there is no lookahead or extra audio buffering.
  for (size_t s = 0; s < bank.coeffs.size(); ++s) {
    const BiquadCoeffs c = bank.coeffs[s];
    if(c.b0==1 && c.b1==0 && c.b2==0 && c.a1==0 && c.a2==0)continue;
    double z1 = bank.z[s][0], z2 = bank.z[s][1];
    for (int i = 0; i < frames; ++i) {
      const double x = data[i] + kDenormGuard;
      const double y = c.b0 * x + z1;
      z1 = c.b1 * x - c.a1 * y + z2;
      z2 = c.b2 * x - c.a2 * y;
      data[i] = y;
    }
    bank.z[s][0] = z1;
    bank.z[s][1] = z2;
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
  for (auto& rt : rt_) {
    if(rt.fadeRemaining>0)rt.active=1-rt.active;
    rt.fadeRemaining=0;rt.initialized=false;
    for(auto& bank:rt.banks)for (auto& z : bank.z) z = {0.0, 0.0};
  }
}

}  // namespace eqcore

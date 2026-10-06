#include "eqcore/stereo.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
std::array<BiquadCoeffs, 10> staticFilters(const StereoTunerParams& p, double fs) {
  auto b = [&](FilterType t, double f, double g, double q) { return designBiquad({t, f, g, q, true}, fs); };
  return {b(FilterType::Peak, 220, 3 * p.warmth, .9), b(FilterType::HighShelf, 8000, -2 * p.warmth, .7),
          b(FilterType::Peak, 1200, 2.5 * p.intimacy, .6), b(FilterType::LowPass, 180, 0, .7071067811865476),
          b(FilterType::HighPass, 180, 0, .7071067811865476), b(FilterType::Peak, 500, 1.5 * p.instruments, 1),
          b(FilterType::Peak, 3000, 4 * p.instruments, .7), b(FilterType::HighShelf, 10000, 3 * p.instruments, .7),
          b(FilterType::Peak, 1600, 2.5 * p.backingVocals, .65), b(FilterType::HighShelf, 4000, 2 * p.spatialDetail, .7)};
}
}  // namespace

std::array<double, 2> stereoResponsePower(const StereoTunerParams& p, double f, double fs) {
  const auto c = staticFilters(p, fs);
  const auto z = std::polar(1.0, -2.0 * 3.14159265358979323846 * f / fs);
  auto h = [&](int i) { const auto& b = c[static_cast<size_t>(i)];
    return (b.b0 + b.b1 * z + b.b2 * z * z) / (1.0 + b.a1 * z + b.a2 * z * z); };
  const auto mid = h(0) * h(1) * h(2);
  const auto side = p.space == 0 && p.instruments == 0 && p.backingVocals == 0 && p.spatialDetail == 0 ? std::complex<double>(1, 0) :
      h(3) * h(3) + std::pow(10.0, 6.0 * std::clamp(p.space, -1.0, 1.0) / 20.0) * h(4) * h(4) * h(5) * h(6) * h(7) * h(8) * h(9);
  return {std::norm(mid), std::norm(side)};
}

StereoTuner::StereoTuner(double sampleRate) : fs_(sampleRate), fadeFrames_(std::max(1,static_cast<int>(sampleRate*.020))) {
  for(auto& state:states_)state.redesign({},fs_);
}

void StereoTuner::setParams(const StereoTunerParams& p) {
  // Tiny spin lock: UI-thread writers only; the audio thread uses try-semantics.
  while (pendingLock_.exchange(true, std::memory_order_acquire)) {
  }
  const auto unit=[](double x){return std::isfinite(x)?std::clamp(x,0.,1.):0.;};
  pending_ = {unit(p.intimacy),unit(p.warmth),unit(p.smoothness),
      std::isfinite(p.space)?std::clamp(p.space,-1.,1.):0.,unit(p.instruments),unit(p.backingVocals),unit(p.spatialDetail)};
  pendingLock_.store(false, std::memory_order_release);
  version_.fetch_add(1, std::memory_order_release);
}

void StereoTuner::State::redesign(const StereoTunerParams& p, double fs_) {
  p_=p;
  aBand_=coeff(1,fs_);rBand_=coeff(60,fs_);aFull_=coeff(5,fs_);rFull_=coeff(150,fs_);gSmooth_=coeff(2,fs_);
  const auto set = [&](Bq& b, FilterType t, double f, double g, double q) {
    b.c = designBiquad({t, f, g, q, true}, fs_);
  };
  const auto c = staticFilters(p, fs_);
  warmBell_.c = c[0]; warmShelf_.c = c[1]; intimacyBell_.c = c[2];
  set(harshBand_, FilterType::BandPass, 3800.0, 0.0, 0.9);
  for (auto& lp : sideLp_) lp.c = c[3];
  for (auto& hp : sideHp_) hp.c = c[4];
  bodyBell_.c = c[5]; presenceBell_.c = c[6]; airShelf_.c = c[7];
  backingBell_.c=c[8];detailShelf_.c=c[9];
  spaceGain_ = std::pow(10.0, 6.0 * std::clamp(p.space, -1.0, 1.0) / 20.0);
}

void StereoTuner::State::reset() {
  for (Bq* b : {&warmBell_, &warmShelf_, &intimacyBell_, &harshBand_, &sideLp_[0], &sideLp_[1], &sideHp_[0], &sideHp_[1], &bodyBell_,
                &presenceBell_, &airShelf_, &backingBell_, &detailShelf_})
    b->z1 = b->z2 = 0;
  envBand_ = envFull_ = 1e-9;
  deharshGain_ = 1.0;
}

double StereoTuner::State::process(double& left, double& right) {
  if (p_.isOff()) {
    return 1.;  // bit-exact passthrough
  }
  const bool vocal = p_.intimacy != 0 || p_.warmth != 0 || p_.smoothness != 0;
  const bool side = p_.space != 0 || p_.instruments != 0 || p_.backingVocals != 0 || p_.spatialDetail != 0;
  // De-harsh: band level relative to the whole voice, above a threshold that
  // drops as smoothness rises; up to 12 dB of reduction.
  const double thrDb = -4.0 - 8.0 * p_.smoothness;
  const double maxRedDb = 12.0 * p_.smoothness;
  double minGain = 1.0;

  double m = 0.5 * (left + right);
  double s = 0.5 * (left - right);
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
    high = detailShelf_.run(backingBell_.run(airShelf_.run(presenceBell_.run(bodyBell_.run(high)))));
    s = low + spaceGain_ * high;
  }
  left = m + s;
  right = m - s;
  return minGain;
}

void StereoTuner::reset() {
  if(fadeRemaining_>0)active_=1-active_;
  fadeRemaining_=0;initialized_=false;lastDeharshDb_=0;
  for(auto& state:states_)state.reset();
}

void StereoTuner::process(double* L,double* R,int frames) {
  if(frames<=0)return;
  const int v=version_.load(std::memory_order_acquire);
  if(fadeRemaining_==0 && v!=appliedVersion_ && !pendingLock_.exchange(true,std::memory_order_acquire)) {
    const auto next=pending_;
    pendingLock_.store(false,std::memory_order_release);
    if(!(next==states_[active_].p_)) {
      states_[1-active_]=states_[active_]; // retain histories; fixed-size, no allocation
      states_[1-active_].redesign(next,fs_);
      if(!initialized_)active_=1-active_;else fadeRemaining_=fadeFrames_;
    }
    appliedVersion_=v;
  }
  initialized_=true;
  double minGain=1.;
  for(int i=0;i<frames;++i) {
    if(fadeRemaining_>0) {
      double l=L[i],r=R[i];
      minGain=std::min(minGain,states_[active_].process(L[i],R[i]));
      minGain=std::min(minGain,states_[1-active_].process(l,r));
      const double t=1.-static_cast<double>(fadeRemaining_)/fadeFrames_;
      L[i]+=t*(l-L[i]);R[i]+=t*(r-R[i]);
      if(--fadeRemaining_==0)active_=1-active_;
    } else minGain=std::min(minGain,states_[active_].process(L[i],R[i]));
  }
  lastDeharshDb_=20*std::log10(minGain);
}

}  // namespace eqcore

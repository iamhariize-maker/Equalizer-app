#include "eqcore/stereo.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }
// Space widening runs through a high shelf whose cut above kSpaceShelfHz equals the widening, so "more space"
// adds width through the body and presence and does not lift the air. The shelf is minimum-phase and acts on
// the high-band delta (hi1) together with the expansion gain, so it adds no extra phase error at 2 kHz (a
// band-limited delta with a 4 kHz low-pass lost about 2 dB there).
constexpr double kSpaceShelfHz = 6000.0;
std::array<BiquadCoeffs, 12> staticFilters(const StereoTunerParams& p, double fs) {
  auto b = [&](FilterType t, double f, double g, double q) { return designBiquad({t, f, g, q, true}, fs); };
  // Expansion of +6 dB at space +1 is 20 log10(g); the shelf takes it back above kSpaceShelfHz. Narrowing keeps its full cut.
  const double shelfDb = p.space > 0 ? -6.0 * std::min(p.space, 1.0) : 0.0;
  return {b(FilterType::Peak, 220, 3 * p.warmth, .9), b(FilterType::HighShelf, 8000, -2 * p.warmth, .7),
          b(FilterType::Peak, 1200, 2.5 * p.intimacy, .6), b(FilterType::LowPass, 180, 0, .7071067811865476),
          b(FilterType::HighPass, 180, 0, .7071067811865476), b(FilterType::Peak, 500, 1.5 * p.instruments, 1),
          b(FilterType::Peak, 3000, 4 * p.instruments, .7), b(FilterType::HighShelf, 10000, 3 * p.instruments, .7),
          b(FilterType::Peak, 1600, 2 * p.backingVocals, .65), b(FilterType::HighShelf, 4000, 1.5 * p.spatialDetail, .7),
          b(FilterType::Peak, 500, 2.5 * p.spatialDetail, .7),
          b(FilterType::HighShelf, kSpaceShelfHz, shelfDb, .7071067811865476)};
}
}  // namespace

std::array<double, 2> stereoResponsePower(const StereoTunerParams& p, double f, double fs) {
  const auto c = staticFilters(p, fs);
  const auto z = std::polar(1.0, -2.0 * 3.14159265358979323846 * f / fs);
  auto h = [&](int i) { const auto& b = c[static_cast<size_t>(i)];
    return (b.b0 + b.b1 * z + b.b2 * z * z) / (1.0 + b.a1 * z + b.a2 * z * z); };
  const auto mid = h(0) * h(1) * h(2);
  const auto side = p.space == 0 && p.instruments == 0 && p.backingVocals == 0 && p.spatialDetail == 0 ? std::complex<double>(1, 0) :
      // Dry side plus a bounded delta on the LR4 high band: 1 + HP^2 * (gain * shaping - 1).
      // Equals 1 exactly at zero control, whatever the crossover phase does. The space shelf (index 11) is
      // part of the gain, so its response is the one the processing applies.
      1.0 + h(4) * h(4) * (std::pow(10.0, 6.0 * std::clamp(p.space, -1.0, 1.0) / 20.0) * h(11) * h(5) * h(6) * h(7) * h(8) * h(9) * h(10) - 1.0);
  return {std::norm(mid), std::norm(side)};
}

StereoTuner::StereoTuner(double sampleRate, bool residual)
    : residual_(residual ? std::make_unique<SpatialResidual>(sampleRate) : nullptr), fs_(sampleRate), fadeFrames_(std::max(1,static_cast<int>(sampleRate*.020))) {
  for(auto& state:states_)state.redesign({},fs_);
  if (residual_) deharshDelay_.assign(static_cast<size_t>(residual_->latencyFrames()), 1.0);
}

void StereoTuner::setParams(const StereoTunerParams& p) {
  // Tiny spin lock: UI-thread writers only; the audio thread uses try-semantics.
  while (pendingLock_.exchange(true, std::memory_order_acquire)) {
  }
  const auto unit=[](double x){return std::isfinite(x)?std::clamp(x,0.,1.):0.;};
  pending_ = {unit(p.intimacy),unit(p.warmth),unit(p.smoothness),
      std::isfinite(p.space)?std::clamp(p.space,-1.,1.):0.,unit(p.instruments),unit(p.backingVocals),unit(p.spatialDetail)};
  // Serialize the shared and residual parameter publications together. Reading
  // pending_ after unlocking would race another UI writer and mix generations.
  if (residual_) residual_->setParams(pending_.backingVocals, pending_.spatialDetail);
  version_.fetch_add(1, std::memory_order_release);
  pendingLock_.store(false, std::memory_order_release);
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
  for (auto& hp : sideHp_) hp.c = c[4];
  bodyBell_.c = c[5]; presenceBell_.c = c[6]; airShelf_.c = c[7];
  backingBell_.c=c[8];detailShelf_.c=c[9];shuffleBell_.c=c[10];spaceShelf_.c=c[11];
  // Vocal layers: 1.2 kHz centre, roughly 450 Hz-3.2 kHz.
  set(vocalSide_, FilterType::BandPass, 1200.0, 0.0, 0.55);
  vocalMid_.c = vocalSide_.c;
  aVocal_=coeff(10,fs_);rVocal_=coeff(120,fs_);aLift_=coeff(40,fs_);rLift_=coeff(250,fs_);
  // Motion: detection ignores the bass below the side crossover.
  for (auto& hp : motionHp_) hp.c = c[4];  // same LR4 high-pass as the side path
  set(splitSide_[0], FilterType::LowPass, 1000.0, 0.0, .7071067811865476);
  set(splitSide_[1], FilterType::LowPass, 4000.0, 0.0, .7071067811865476);
  splitMid_[0].c=splitSide_[0].c;splitMid_[1].c=splitSide_[1].c;
  aBudget_=coeff(150,fs_);aBudgetUp_=coeff(100,fs_);
  aPan_=coeff(15,fs_);aSlow_=coeff(600,fs_);aMotionUp_=coeff(10,fs_);aMotionDown_=coeff(300,fs_);
  spaceGain_ = std::pow(10.0, 6.0 * std::clamp(p.space, -1.0, 1.0) / 20.0);
  // Individually bypassed detectors also stop advancing while other groups
  // remain active. Store a clean off state for any later reactivation.
  if (p.smoothness == 0) resetSmoothHistory();
  if (p.backingVocals == 0) resetBackingHistory();
  if (p.spatialDetail == 0) resetMotionHistory();
}

void StereoTuner::State::reset() {
  resetVocalHistory();
  resetSideHistory();
  resetFastHistory();
  vocalActive_ = sideActive_ = fastActive_ = false;
}

void StereoTuner::State::resetVocalHistory() {
  for (Bq* b : {&warmBell_, &warmShelf_, &intimacyBell_}) b->z1 = b->z2 = 0;
  resetSmoothHistory();
}

void StereoTuner::State::resetSmoothHistory() {
  harshBand_.z1 = harshBand_.z2 = 0;
  envBand_ = envFull_ = 1e-9;
  deharshGain_ = 1.0;
}

void StereoTuner::State::resetSideHistory() {
  for (Bq* b : {&sideHp_[0], &sideHp_[1], &bodyBell_, &presenceBell_, &airShelf_, &spaceShelf_}) b->z1 = b->z2 = 0;
}

void StereoTuner::State::resetFastHistory() {
  resetBackingHistory();
  resetMotionHistory();
  budgetMm_ = budgetSs_ = budgetSd_ = budgetDd_ = 0;
  budgetScale_ = 1;
}

void StereoTuner::State::resetBackingHistory() {
  for (Bq* b : {&backingBell_, &vocalSide_, &vocalMid_}) b->z1 = b->z2 = 0;
  envVocalSide_ = envVocalMid_ = backingLiftDb_ = 0;
}

void StereoTuner::State::resetMotionHistory() {
  for (Bq* b : {&detailShelf_, &shuffleBell_, &motionHp_[0], &motionHp_[1], &splitSide_[0], &splitSide_[1], &splitMid_[0], &splitMid_[1]})
    b->z1 = b->z2 = 0;
  for (int b = 0; b < kBands; ++b) { cross_[b] = power_[b] = panSlow_[b] = 0; motionGain_[b] = 1; heard_[b] = false; }
}

double StereoTuner::State::process(double& left, double& right, bool fastSpatial, double* fastDelta) {
  if (fastDelta) *fastDelta = 0;
  if (p_.isOff()) {
    vocalActive_ = sideActive_ = fastActive_ = false;
    return 1.;  // bit-exact passthrough
  }
  const bool vocal = p_.intimacy != 0 || p_.warmth != 0 || p_.smoothness != 0;
  const bool side = p_.space != 0 || p_.instruments != 0 ||
      (fastSpatial && (p_.backingVocals != 0 || p_.spatialDetail != 0));
  const bool fast = fastSpatial && (p_.backingVocals != 0 || p_.spatialDetail != 0);
  // A suspended group has not followed the current recording. Its old tails
  // must not be replayed on resume. Clear only that group; active shared paths
  // retain their histories across Fast/Detailed changes and parameter fades.
  if (vocal && !vocalActive_) resetVocalHistory();
  if (side && !sideActive_) resetSideHistory();
  if (fast && !fastActive_) resetFastHistory();
  vocalActive_ = vocal;
  sideActive_ = side;
  fastActive_ = fast;
  if (!vocal && !side) return 1.;
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
    // Dry side plus a bounded delta. The side signal itself is never filtered through
    // the 180 Hz crossover (its all-pass phase rotated S against M and swapped hard-panned
    // bass between channels); only the *difference* between the shaped and plain high
    // band is added, so zero control is identity despite the crossover phase.
    // Explicit Space/Instruments still alter width and relative M/S phase.
    const double plain = sideHp_[1].run(sideHp_[0].run(s));
    const double instr = bodyBell_.run(plain);
    const double hi1 = airShelf_.run(presenceBell_.run(instr));
    // Space: gain with the high shelf that returns the air to unity (see staticFilters). Instruments still shapes the top.
    s += spaceGain_ * spaceShelf_.run(hi1) - plain;
    if (fastSpatial && (p_.backingVocals > 0 || p_.spatialDetail > 0)) {
      double high = detailShelf_.run(backingBell_.run(hi1));
      if (p_.spatialDetail > 0) high = shuffleBell_.run(high);
      if (p_.backingVocals > 0) high = backingLift(high, m);
      if (p_.spatialDetail > 0) high = motion(high, m);
      const double delta = budgetedSpatialDelta(s, m, spaceGain_ * (high - hi1));
      if (fastDelta) *fastDelta = delta; else s += delta;
    }
  }
  left = m + s;
  right = m - s;
  return minGain;
}

// Side-energy budget for the automatic spatial detail (Backing vocals + Binaural). With P the
// smoothed (150 ms) powers of mid (Pmm) and of the side before this delta (Pss), q = E[s d] and
// Pdd = E[d^2], the output side power is Pss + 2 a q + a^2 Pdd. The delta may not take it past
// min(0.5 Pmm, Pss * 10^(4/10)): the side stays under half the mid power and gains at most 4 dB.
// A side already at the limit gets no further lift; a delta that narrows is never limited.
// The scale falls at once and recovers over 100 ms so the budget is never overspent.
double StereoTuner::State::budgetedSpatialDelta(double s, double m, double d) {
  budgetMm_ = aBudget_ * budgetMm_ + (1 - aBudget_) * m * m;
  budgetSs_ = aBudget_ * budgetSs_ + (1 - aBudget_) * s * s;
  budgetSd_ = aBudget_ * budgetSd_ + (1 - aBudget_) * s * d;
  budgetDd_ = aBudget_ * budgetDd_ + (1 - aBudget_) * d * d;
  double a = 1.0;
  const double pss = budgetSs_, q = budgetSd_, pdd = budgetDd_;
  if (budgetMm_ > 1e-8 && pdd > 1e-18) {  // a mid above about -80 dBFS and a real delta to judge
    const double limit = std::max(std::min(0.5 * budgetMm_, pss * 2.5118864315095801), pss);
    if (pss + 2 * q + pdd > limit) {
      const double slack = limit - pss;  // >= 0
      const double disc = std::sqrt(q * q + pdd * slack);
      a = std::clamp(q >= 0 ? slack / (disc + q + 1e-300) : (disc - q) / pdd, 0.0, 1.0);
    }
  }
  budgetScale_ = a < budgetScale_ ? a : aBudgetUp_ * budgetScale_ + (1 - aBudgetUp_) * a;
  return budgetScale_ * d;
}

// Backing vocals are commonly doubled/harmony layers spread off-centre, where
// the lead sits in the middle. When the side vocal band is far quieter than the
// centre vocal band (layers masked by the lead), lift it; when the layers are
// already comparable, leave them. A peaking structure: x + (g-1)*bandpass(x).
double StereoTuner::State::backingLift(double high, double m) {
  const double vs = vocalSide_.run(high), vm = vocalMid_.run(m);
  const double as = std::fabs(vs), am = std::fabs(vm);
  envVocalSide_ = as > envVocalSide_ ? aVocal_ * envVocalSide_ + (1 - aVocal_) * as : rVocal_ * envVocalSide_ + (1 - rVocal_) * as;
  envVocalMid_ = am > envVocalMid_ ? aVocal_ * envVocalMid_ + (1 - aVocal_) * am : rVocal_ * envVocalMid_ + (1 - rVocal_) * am;
  double targetDb = 0;
  if (envVocalMid_ > 1e-4) {  // a centre vocal band above about -80 dBFS
    const double ratio = envVocalSide_ / envVocalMid_;
    targetDb = 4.0 * p_.backingVocals * std::clamp((0.5 - ratio) / 0.4, 0.0, 1.0);
  }
  const double a = targetDb > backingLiftDb_ ? aLift_ : rLift_;
  backingLiftDb_ = a * backingLiftDb_ + (1 - a) * targetDb;
  return high + (std::pow(10.0, backingLiftDb_ / 20.0) - 1.0) * vs;
}

// Per band, the signed position p = 2<ms>/<m^2+s^2> is +1 hard left, -1 hard
// right and 0 centred. Its distance from a slow (600 ms) average is the motion
// the artist put into the mix: ping-pong delays, auto-pans, panned fills. Only
// moving bands get extra side level, so static images keep their placement.
// The three bands are a complementary split and sum back exactly.
double StereoTuner::State::motion(double high, double m) {
  const double md = motionHp_[1].run(motionHp_[0].run(m));
  const double s0 = splitSide_[0].run(high), m0 = splitMid_[0].run(md);
  const double sr = high - s0, mr = md - m0;
  const double s1 = splitSide_[1].run(sr), m1 = splitMid_[1].run(mr);
  const double sb[kBands] = {s0, s1, sr - s1}, mb[kBands] = {m0, m1, mr - m1};
  double out = 0;
  for (int b = 0; b < kBands; ++b) {
    cross_[b] = aPan_ * cross_[b] + (1 - aPan_) * sb[b] * mb[b];
    power_[b] = aPan_ * power_[b] + (1 - aPan_) * (sb[b] * sb[b] + mb[b] * mb[b]);
    double target = 1;
    if (power_[b] > 1e-8) {  // above about -80 dBFS
      const double pan = std::clamp(2 * cross_[b] / power_[b], -1.0, 1.0);
      // A band emerging from silence starts where it is: an entry is not movement.
      panSlow_[b] = heard_[b] ? aSlow_ * panSlow_[b] + (1 - aSlow_) * pan : pan;
      heard_[b] = true;
      target = 1 + 0.8 * p_.spatialDetail * std::clamp(std::fabs(pan - panSlow_[b]) / 0.6, 0.0, 1.0);
    }
    else heard_[b] = false;
    const double a = target > motionGain_[b] ? aMotionUp_ : aMotionDown_;
    motionGain_[b] = a * motionGain_[b] + (1 - a) * target;
    out += motionGain_[b] * sb[b];
  }
  return out;
}

void StereoTuner::reset() {
  if(fadeRemaining_>0)active_=1-active_;
  fadeRemaining_=0;initialized_=false;lastDeharshDb_=0;
  if(residual_)residual_->reset();
  std::fill(deharshDelay_.begin(), deharshDelay_.end(), 1.0); deharshPos_ = 0;
  for(auto& state:states_)state.reset();
}

void StereoTuner::process(double* L,double* R,int frames,double* deharshGains) {
  if(frames<=0)return;
  const int v=version_.load(std::memory_order_acquire);
  if(fadeRemaining_==0 && v!=appliedVersion_ && !pendingLock_.exchange(true,std::memory_order_acquire)) {
    const auto next=pending_;
    pendingLock_.store(false,std::memory_order_release);
    if(!(next==states_[active_].p_)) {
      states_[1-active_]=states_[active_]; // retain histories; fixed-size, no allocation
      auto designed=next;
      states_[1-active_].redesign(designed,fs_);
      if(!initialized_)active_=1-active_;else fadeRemaining_=fadeFrames_;
    }
    appliedVersion_=v;
  }
  initialized_=true;
  double minGain=1.;
  // Fixed stack chunks feed both spatial paths through the same delay. Only
  // spatial deltas blend; the vocal/Space/Instruments path is shared.
  double m[256],s[256],fast[256];
  for(int base=0;base<frames;base+=256) {
   const int count=std::min(256,frames-base);
   const bool needFast = !residual_ || residual_->needsFastPath();
   for(int j=0;j<count;++j) {
    const int i=base+j;
    double d=0;
    double deharsh = 1;
    if(fadeRemaining_>0) {
      double l=L[i],r=R[i];
      double nextDelta=0;
      const double oldGain=states_[active_].process(L[i],R[i],needFast,residual_?&d:nullptr);
      const double nextGain=states_[1-active_].process(l,r,needFast,residual_?&nextDelta:nullptr);
      minGain=std::min({minGain,oldGain,nextGain});
      const double t=1.-static_cast<double>(fadeRemaining_)/fadeFrames_;
      deharsh=oldGain+t*(nextGain-oldGain);
      L[i]+=t*(l-L[i]);R[i]+=t*(r-R[i]);
      d+=t*(nextDelta-d);
      if(--fadeRemaining_==0)active_=1-active_;
    } else { deharsh=states_[active_].process(L[i],R[i],needFast,residual_?&d:nullptr); minGain=std::min(minGain,deharsh); }
    if(residual_) {
      const double delayed = deharshDelay_[deharshPos_];
      deharshDelay_[deharshPos_] = deharsh;
      if(++deharshPos_ == deharshDelay_.size()) deharshPos_ = 0;
      deharsh = delayed;
    }
    if(deharshGains) deharshGains[i] = deharsh;
    if(residual_) { m[j]=.5*(L[i]+R[i]);s[j]=.5*(L[i]-R[i]);fast[j]=d; }
   }
   if(residual_) {
      residual_->process(m,s,count,fast);
      for(int j=0;j<count;++j){L[base+j]=m[j]+s[j];R[base+j]=m[j]-s[j];}
    }
  }
  lastDeharshDb_=20*std::log10(minGain);
}

}  // namespace eqcore

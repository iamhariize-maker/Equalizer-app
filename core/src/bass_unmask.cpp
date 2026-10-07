#include "eqcore/bass_unmask.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

BassUnmask::BassUnmask(double fs)
    : fs_(fs), decim_(std::max(1, static_cast<int>(std::lround(fs / 2000.0)))), fsd_(fs / std::max(1, static_cast<int>(std::lround(fs / 2000.0)))) {
  // 8th-order Butterworth low-pass at 500 Hz (four biquads) before decimation.
  const double q[4] = {0.5097955791, 0.6013448869, 0.8999762231, 2.5629154477};
  for (int i = 0; i < 4; ++i) lp_[i].setCoeffs(designBiquad({FilterType::LowPass, 500.0, 0.0, q[i], true}, fs));
  decimated_.assign(static_cast<size_t>(ring_), 0.0);
  window_.resize(static_cast<size_t>(ring_));
  for (int i = 0; i < ring_; ++i) window_[static_cast<size_t>(i)] = 0.5 - 0.5 * std::cos(2 * kPi * i / ring_);
  buf_.resize(static_cast<size_t>(fftN_));
  mag_.resize(static_cast<size_t>(fftN_ / 2 + 1));
  twiddle_.resize(static_cast<size_t>(fftN_ / 2));
  for (int i = 0; i < fftN_ / 2; ++i) twiddle_[static_cast<size_t>(i)] = std::polar(1.0, -2 * kPi * i / fftN_);
  bitrev_.resize(static_cast<size_t>(fftN_));
  int bits = 0;
  while ((1 << bits) < fftN_) ++bits;
  for (int i = 0; i < fftN_; ++i) {
    int r = 0;
    for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
    bitrev_[static_cast<size_t>(i)] = r;
  }
  aAttack_ = std::exp(-1.0 / (0.050 * fs));
  aRelease_ = std::exp(-1.0 / (0.400 * fs));
  aFastRelease_ = std::exp(-1.0 / (0.030 * fs));
  aFastUp_ = std::exp(-1.0 / (0.001 * fs));
  aFastDn_ = std::exp(-1.0 / (0.020 * fs));
  aSlowUp_ = std::exp(-1.0 / (0.030 * fs));
  aSlowDn_ = std::exp(-1.0 / (0.100 * fs));
  onsetBlockFrames_ = static_cast<int>(0.100 * fs);
  reset();
}

void BassUnmask::setAmount(double a) { amount_.store(std::isfinite(a) ? std::clamp(a, 0.0, 1.0) : 0.0, std::memory_order_relaxed); }

void BassUnmask::reset() {
  for (auto& f : lp_) f.reset();
  std::fill(decimated_.begin(), decimated_.end(), 0.0);
  phase_ = sinceFrame_ = ringPos_ = 0;
  noteValid_ = false; noteHz_ = 0; noteDb_ = -200; confidence_ = 0; stableFrames_ = 0;
  qualified_.fill(0); targetDb_.fill(0); cutDb_.fill(0); excess_.fill(0); designedDb_.fill(0);
  for (auto& ch : filter_) for (int b = 0; b < kLanes; ++b) { ch[static_cast<size_t>(b)].reset(); ch[static_cast<size_t>(b)].setCoeffs(BiquadCoeffs{}); }
  envFast_ = envSlow_ = 0; onsetBlock_ = 0; onsetActive_ = false;
  cutting_.store(false, std::memory_order_relaxed);
}

void BassUnmask::fft(Cx* x) const {
  const int n = fftN_;
  for (int i = 0; i < n; ++i) { const int j = bitrev_[static_cast<size_t>(i)]; if (j > i) std::swap(x[i], x[j]); }
  for (int len = 2; len <= n; len <<= 1) {
    const int half = len / 2, step = n / len;
    for (int i = 0; i < n; i += len)
      for (int k = 0; k < half; ++k) {
        const Cx a = x[i + k], b = x[i + k + half] * twiddle_[static_cast<size_t>(k * step)];
        x[i + k] = a + b; x[i + k + half] = a - b;
      }
  }
}

void BassUnmask::applyGains() {
  // Combined cut <= 2 dB, applied to the actual smoothed values (not to the targets).
  double sum = 0;
  for (double c : cutDb_) sum -= c;
  if (sum > 2.0) for (double& c : cutDb_) c *= 2.0 / sum;
}

void BassUnmask::frame() {
  // Window the last ring_ decimated samples (oldest first), zero-pad, FFT.
  const double norm = 2.0 / (0.5 * ring_);   // Hann coherent gain 0.5: a full-scale sine reads as its amplitude
  double energy = 0;
  for (int i = 0; i < ring_; ++i) {
    const double v = decimated_[static_cast<size_t>((ringPos_ + i) % ring_)];
    buf_[static_cast<size_t>(i)] = Cx(v * window_[static_cast<size_t>(i)], 0);
    energy += v * v;
  }
  for (int i = ring_; i < fftN_; ++i) buf_[static_cast<size_t>(i)] = Cx(0, 0);
  fft(buf_.data());
  const double binHz = fsd_ / fftN_;
  const int hi = std::min(fftN_ / 2, static_cast<int>(700.0 / binHz));
  for (int k = 0; k <= hi; ++k) mag_[static_cast<size_t>(k)] = std::abs(buf_[static_cast<size_t>(k)]) * norm * 0.5;
  const bool loud = std::sqrt(energy / ring_) > 3e-4 * 0.7071;  // > about -70 dBFS rms
  auto db = [&](int k) { return 20 * std::log10(std::max(1e-12, mag_[static_cast<size_t>(k)])); };
  // Local maxima 35..600 Hz with parabolic interpolation.
  std::array<Peak, 24> peaks{};
  int np = 0;
  const int lo = std::max(2, static_cast<int>(30.0 / binHz)), top = std::min(hi - 1, static_cast<int>(600.0 / binHz));
  for (int k = lo; k <= top && loud; ++k) {
    const double a = db(k - 1), b = db(k), c = db(k + 1);
    if (b > a && b >= c && b > -66.0) {
      const double den = a - 2 * b + c, d = den < -1e-9 ? 0.5 * (a - c) / den : 0.0;
      const Peak p{(k + std::clamp(d, -0.5, 0.5)) * binHz, b - 0.25 * (a - c) * d};
      if (np < static_cast<int>(peaks.size())) peaks[static_cast<size_t>(np++)] = p;
      else {  // keep the strongest
        int w = 0;
        for (int i = 1; i < np; ++i) if (peaks[static_cast<size_t>(i)].db < peaks[static_cast<size_t>(w)].db) w = i;
        if (p.db > peaks[static_cast<size_t>(w)].db) peaks[static_cast<size_t>(w)] = p;
      }
    }
  }
  // Note detection.
  bool found = false;
  double bestHz = 0, bestDb = -200, bestConf = 0;
  for (int i = 0; i < np; ++i) {
    const Peak& f0 = peaks[static_cast<size_t>(i)];
    if (f0.hz < 35.0 || f0.hz > 200.0) continue;
    int partials = 0;
    for (int h = 2; h <= 5; ++h) {
      const double target = h * f0.hz, tol = std::max(0.035 * target, 4.0);
      for (int j = 0; j < np; ++j) {
        const Peak& q = peaks[static_cast<size_t>(j)];
        if (std::fabs(q.hz - target) <= tol && q.db >= f0.db - 30.0) { ++partials; break; }
      }
    }
    if (partials >= 2 && (!found || f0.db > bestDb)) { found = true; bestHz = f0.hz; bestDb = f0.db; bestConf = std::min(1.0, 0.5 + 0.25 * (partials - 1)); }
  }
  if (found && noteHz_ > 0 && std::fabs(bestHz - noteHz_) <= 0.03 * noteHz_) stableFrames_ = std::min(stableFrames_ + 1, 1000);
  else stableFrames_ = found ? 1 : 0;
  noteHz_ = found ? bestHz : 0.0;
  noteDb_ = found ? bestDb : -200.0;
  confidence_ = found ? bestConf : 0.0;
  noteValid_ = found && stableFrames_ >= 3;
  // Lane evidence.
  const auto lanes = laneHz();
  for (int l = 0; l < kLanes; ++l) {
    const size_t sl = static_cast<size_t>(l);
    bool qualifies = false;
    double excess = 0;
    if (noteValid_) {
      const double fc = lanes[sl];
      const Peak* best = nullptr;
      for (int i = 0; i < np; ++i) {
        const Peak& p = peaks[static_cast<size_t>(i)];
        if (p.hz >= fc / 1.25 && p.hz <= fc * 1.25 && (!best || p.db > best->db)) best = &p;
      }
      if (best) {
        bool protectedPeak = best->hz < 0.9 * noteHz_;
        for (int h = 1; h <= 8 && !protectedPeak; ++h)
          if (std::fabs(best->hz - h * noteHz_) <= std::max(0.04 * h * noteHz_, 5.0)) protectedPeak = true;
        excess = best->db - noteDb_;
        qualifies = !protectedPeak && excess > 6.0;
      }
    }
    qualified_[sl] = qualifies ? std::min(qualified_[sl] + 1, 1000) : 0;
    excess_[sl] = excess;
    const bool sustained = qualified_[sl] >= 5 && !onsetActive_ && onsetBlock_ == 0;
    targetDb_[sl] = sustained ? -amountApplied_ * confidence_ * std::clamp(0.5 * (excess - 6.0), 0.0, 1.5) : 0.0;
  }
}

void BassUnmask::process(double* left, double* right, int frames) {
  const double requested = amount_.load(std::memory_order_relaxed);
  if (requested == 0.0 && amountApplied_ == 0.0 && !cutting_.load(std::memory_order_relaxed)) return;  // exact bypass
  amountApplied_ = requested;
  const auto lanes = laneHz();
  bool any = false;
  for (int i = 0; i < frames; ++i) {
    const double xl = left[i], xr = right ? right[i] : xl;
    // Analysis path: mono mix -> low-pass -> onset envelopes and decimation.
    double v = 0.5 * (xl + xr);
    for (auto& f : lp_) v = f.process(v);
    const double a = std::fabs(v);
    envFast_ = a > envFast_ ? aFastUp_ * envFast_ + (1 - aFastUp_) * a : aFastDn_ * envFast_ + (1 - aFastDn_) * a;
    envSlow_ = a > envSlow_ ? aSlowUp_ * envSlow_ + (1 - aSlowUp_) * a : aSlowDn_ * envSlow_ + (1 - aSlowDn_) * a;
    const bool onset = envSlow_ > 1e-4 && envFast_ > 2.0 * envSlow_;  // 6 dB
    if (onset) onsetBlock_ = onsetBlockFrames_;
    else if (onsetBlock_ > 0) --onsetBlock_;
    onsetActive_ = onset;
    if (++phase_ >= decim_) {
      phase_ = 0;
      decimated_[static_cast<size_t>(ringPos_)] = v;
      ringPos_ = (ringPos_ + 1) % ring_;
      if (++sinceFrame_ >= 64) { sinceFrame_ = 0; frame(); }
    }
    // Per-sample smoothing of each lane's cut; fast release while an onset is blocking.
    for (int l = 0; l < kLanes; ++l) {
      const size_t sl = static_cast<size_t>(l);
      const double target = (onsetBlock_ > 0 || requested == 0.0) ? 0.0 : targetDb_[sl];
      const double c = target < cutDb_[sl] ? aAttack_ : (onsetBlock_ > 0 || requested == 0.0 ? aFastRelease_ : aRelease_);
      cutDb_[sl] = target + c * (cutDb_[sl] - target);
      if (std::fabs(cutDb_[sl]) < 1e-4 && target == 0.0) cutDb_[sl] = 0.0;
    }
    applyGains();
    // Re-design a lane only when its gain moved by more than 0.01 dB; run filters in series on both channels.
    double yl = xl, yr = xr;
    for (int l = 0; l < kLanes; ++l) {
      const size_t sl = static_cast<size_t>(l);
      if (std::fabs(cutDb_[sl] - designedDb_[sl]) > 0.01 || (cutDb_[sl] == 0.0 && designedDb_[sl] != 0.0)) {
        designedDb_[sl] = cutDb_[sl];
        const BiquadCoeffs c = cutDb_[sl] == 0.0 ? BiquadCoeffs{} : designBiquad({FilterType::Peak, lanes[sl], cutDb_[sl], 1.4, true}, fs_);
        filter_[0][sl].setCoeffs(c);
        filter_[1][sl].setCoeffs(c);
      }
      yl = filter_[0][sl].process(yl);
      yr = filter_[1][sl].process(yr);
      any = any || cutDb_[sl] != 0.0;
    }
    left[i] = yl;
    if (right) right[i] = yr;
  }
  bool strong = false;
  for (double c : cutDb_) strong = strong || c < -0.05;
  cutting_.store(strong || any, std::memory_order_relaxed);
}

}  // namespace eqcore

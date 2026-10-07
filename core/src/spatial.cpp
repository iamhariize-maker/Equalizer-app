#include "eqcore/spatial.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
double smooth01(double x) { x = std::clamp(x, 0.0, 1.0); return x * x * (3 - 2 * x); }
// 0 below lo, 1 above hi, smooth in log-frequency.
double riseLog(double f, double lo, double hi) { return f <= lo ? 0 : f >= hi ? 1 : smooth01(std::log(f / lo) / std::log(hi / lo)); }
double fallLog(double f, double lo, double hi) { return 1.0 - riseLog(f, lo, hi); }
int nextPow2(double x) { int n = 1; while (n < x) n <<= 1; return n; }
}  // namespace

SpatialResidual::SpatialResidual(double fs)
    : fs_(fs), n_(std::max(64, nextPow2(fs * 1024.0 / 48000.0))), hop_(n_ / 4), bins_(n_ / 2 + 1) {
  window_.resize(static_cast<size_t>(n_));
  for (int i = 0; i < n_; ++i) window_[static_cast<size_t>(i)] = std::sqrt(0.5 - 0.5 * std::cos(2 * kPi * i / n_));  // periodic
  twiddle_.resize(static_cast<size_t>(n_ / 2));
  for (int i = 0; i < n_ / 2; ++i) twiddle_[static_cast<size_t>(i)] = std::polar(1.0, -2 * kPi * i / n_);
  bitrev_.resize(static_cast<size_t>(n_));
  int bits = 0;
  while ((1 << bits) < n_) ++bits;
  for (int i = 0; i < n_; ++i) {
    int r = 0;
    for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
    bitrev_[static_cast<size_t>(i)] = r;
  }
  midRing_.assign(static_cast<size_t>(n_), 0.0);
  sideRing_.assign(static_cast<size_t>(n_), 0.0);
  acc_.assign(static_cast<size_t>(n_), 0.0);
  work_.resize(static_cast<size_t>(n_));
  spec_.resize(static_cast<size_t>(n_));
  maskBacking_.resize(static_cast<size_t>(bins_));
  maskBinaural_.resize(static_cast<size_t>(bins_));
  for (int k = 0; k < bins_; ++k) {
    const double f = k * fs / n_;
    // Backing: vocal-body band 250-450 Hz rise, flat, 3.5-6 kHz taper. Binaural: 180-350 Hz rise, 4-12 kHz taper.
    maskBacking_[static_cast<size_t>(k)] = riseLog(f, 250, 450) * fallLog(f, 3500, 6000);
    maskBinaural_[static_cast<size_t>(k)] = riseLog(f, 180, 350) * fallLog(f, 4000, 12000);
  }
  for (auto* v : {&pmm_, &pss_, &paSlow_, &paFast_, &scale_, &gain_}) v->assign(static_cast<size_t>(bins_), 0.0);
  csm_.assign(static_cast<size_t>(bins_), Cx(0, 0));
  const double frameSec = static_cast<double>(hop_) / fs;
  aSlow_ = std::exp(-frameSec / 0.150);
  aFast_ = std::exp(-frameSec / 0.030);
  aUp_ = std::exp(-frameSec / 0.100);
  aParam_ = std::exp(-frameSec / 0.020);
  aGuard_ = std::exp(-frameSec / 0.050);
  warmFrames_ = 0.2 / frameSec;
  reset();
}

void SpatialResidual::setParams(double backing, double binaural) {
  const auto unit = [](double x) { return std::isfinite(x) ? std::clamp(x, 0.0, 1.0) : 0.0; };
  backing_.store(unit(backing), std::memory_order_relaxed);
  binaural_.store(unit(binaural), std::memory_order_relaxed);
}

void SpatialResidual::reset() {
  std::fill(midRing_.begin(), midRing_.end(), 0.0);
  std::fill(sideRing_.begin(), sideRing_.end(), 0.0);
  std::fill(acc_.begin(), acc_.end(), 0.0);
  for (auto* v : {&pmm_, &pss_, &paSlow_, &paFast_, &scale_}) std::fill(v->begin(), v->end(), 0.0);
  std::fill(gain_.begin(), gain_.end(), 0.0);
  std::fill(csm_.begin(), csm_.end(), Cx(0, 0));
  for (auto& s : scale_) s = 1.0;
  pos_ = 0;
  sinceFrame_ = 0;
  guard_ = prevMid_ = backingSm_ = binauralSm_ = 0;
  lastDeltaDb_ = 0;
}

void SpatialResidual::fft(Cx* x, bool inverse) const {
  for (int i = 0; i < n_; ++i) {
    const int j = bitrev_[static_cast<size_t>(i)];
    if (j > i) std::swap(x[i], x[j]);
  }
  for (int len = 2; len <= n_; len <<= 1) {
    const int half = len / 2, step = n_ / len;
    for (int i = 0; i < n_; i += len) {
      for (int k = 0; k < half; ++k) {
        Cx w = twiddle_[static_cast<size_t>(k * step)];
        if (inverse) w = std::conj(w);
        const Cx a = x[i + k], b = x[i + k + half] * w;
        x[i + k] = a + b;
        x[i + k + half] = a - b;
      }
    }
  }
}

void SpatialResidual::process(double* mid, double* side, int frames) {
  const double bTarget = backing_.load(std::memory_order_relaxed), nTarget = binaural_.load(std::memory_order_relaxed);
  for (int i = 0; i < frames; ++i) {
    const size_t slot = static_cast<size_t>(pos_ % n_);
    const double dryM = midRing_[slot], dryS = sideRing_[slot];   // the sample from exactly n_ frames ago
    const double delta = acc_[slot];
    acc_[slot] = 0.0;
    const double xm = std::isfinite(mid[i]) ? mid[i] : 0.0, xs = std::isfinite(side[i]) ? side[i] : 0.0;
    midRing_[slot] = xm;
    sideRing_[slot] = xs;
    mid[i] = dryM;
    side[i] = dryS + delta;
    ++pos_;
    if (++sinceFrame_ == hop_) {
      sinceFrame_ = 0;
      backingSm_ = aParam_ * backingSm_ + (1 - aParam_) * bTarget;
      binauralSm_ = aParam_ * binauralSm_ + (1 - aParam_) * nTarget;
      if (backingSm_ < 1e-6 && bTarget == 0) backingSm_ = 0;
      if (binauralSm_ < 1e-6 && nTarget == 0) binauralSm_ = 0;
      runFrame();
    }
  }
}

void SpatialResidual::runFrame() {
  const int n = n_;
  // Window the last n_ samples (oldest first) of mid and side into one complex FFT: z = M + iS.
  const size_t start = static_cast<size_t>(pos_ % n);
  double midPow = 0;
  for (int i = 0; i < n; ++i) {
    const size_t idx = (start + static_cast<size_t>(i)) % static_cast<size_t>(n);
    const double w = window_[static_cast<size_t>(i)];
    work_[static_cast<size_t>(i)] = Cx(midRing_[idx] * w, sideRing_[idx] * w);
    midPow += midRing_[idx] * midRing_[idx];
  }
  midPow /= n;
  // Frame onset guard: a sudden rise in mid power holds the enhancement back (50 ms decay).
  const double rise = midPow > 1e-9 ? midPow / (prevMid_ + 1e-9) : 1.0;
  guard_ = std::max(aGuard_ * guard_, rise > 4.0 ? 1.0 : 0.0);  // > 6 dB in one hop
  prevMid_ = midPow;
  fft(work_.data(), false);
  const double norm = 1.0 / n;
  const double warm = std::clamp((static_cast<double>(pos_) / hop_ - warmFrames_) / (0.5 * warmFrames_), 0.0, 1.0);  // 200 ms warm-up, 100 ms ramp
  const bool active = backingSm_ > 0 || binauralSm_ > 0 || std::any_of(gain_.begin(), gain_.end(), [](double g) { return g != 0; });

  const double reqBackingDb = 4.0 * backingSm_, reqBinauralDb = 3.0 * binauralSm_;
  double deltaDbSum = 0, deltaDbCount = 0;
  std::fill(spec_.begin(), spec_.end(), Cx(0, 0));
  for (int k = 1; k < bins_ - 1; ++k) {
    const size_t sk = static_cast<size_t>(k), nk = static_cast<size_t>(n - k);
    const Cx z = work_[sk], zc = std::conj(work_[nk]);
    const Cx m = 0.5 * (z + zc) * norm, s = Cx(0, -0.5) * (z - zc) * norm;
    pmm_[sk] = aSlow_ * pmm_[sk] + (1 - aSlow_) * std::norm(m);
    pss_[sk] = aSlow_ * pss_[sk] + (1 - aSlow_) * std::norm(s);
    csm_[sk] = aSlow_ * csm_[sk] + (1 - aSlow_) * (s * std::conj(m));
    const double pmm = pmm_[sk], pss = pss_[sk];
    const Cx beta = csm_[sk] / (pmm + 1e-14);
    const Cx a = s - beta * m;                                         // residual of this frame
    const double paInst = std::norm(a);
    paSlow_[sk] = aSlow_ * paSlow_[sk] + (1 - aSlow_) * paInst;
    paFast_[sk] = aFast_ * paFast_[sk] + (1 - aFast_) * paInst;
    // Request, in dB of residual boost, shaped by the band masks.
    double req = std::min(4.0, reqBackingDb * maskBacking_[sk] + reqBinauralDb * maskBinaural_[sk]);
    double g = 0;
    if (req > 0 && warm > 0 && pmm > 1e-14 && midPow > 1e-8) {
      const double coh = std::norm(csm_[sk]) / (pmm * pss + 1e-30);
      const double eCoh = smooth01((0.98 - coh) / 0.18);                // 0 at >= 0.98, 1 at <= 0.80
      // A residual far above its own long-term level is an onset or a moving source, not ambience.
      const double eMotion = paFast_[sk] > 2.0 * paSlow_[sk] ? std::clamp(2.0 * paSlow_[sk] / (paFast_[sk] + 1e-30), 0.0, 1.0) : 1.0;
      const double k0 = (std::pow(10.0, req / 20.0) - 1.0) * eCoh * eMotion * (1.0 - guard_) * warm;
      if (k0 > 0) {
        // Exact side-power budget for this bin: Pout = Pss + 2 a q + a^2 Pdd, D = k0 * A.
        const double paSl = std::max(0.0, pss - 2.0 * std::real(std::conj(beta) * csm_[sk]) + std::norm(beta) * pmm);
        const double q = k0 * (pss - std::real(std::conj(beta) * csm_[sk]));
        const double pdd = k0 * k0 * paSl;
        double scale = 1.0;
        const double limit = std::max(std::min(0.5 * pmm, pss * 2.5118864315095801), pss);
        if (pdd > 1e-30 && pss + 2 * q + pdd > limit) {
          const double slack = limit - pss;
          const double disc = std::sqrt(std::max(0.0, q * q + pdd * slack));
          scale = std::clamp(q >= 0 ? slack / (disc + q + 1e-300) : (disc - q) / pdd, 0.0, 1.0);
        }
        scale_[sk] = scale < scale_[sk] ? scale : aUp_ * scale_[sk] + (1 - aUp_) * scale;   // falls at once, recovers slowly
        g = k0 * scale_[sk];
      }
    }
    // Smooth the gain over time (instant fall) so eligibility changes never switch bins on and off.
    gain_[sk] = g < gain_[sk] ? g : aParam_ * gain_[sk] + (1 - aParam_) * g;
    if (gain_[sk] < 1e-9) gain_[sk] = 0;
    if (gain_[sk] > 0) {
      // Frequency anti-chatter: average with the neighbours' gains, never above this bin's own budget.
      const double gl = k > 1 ? gain_[sk - 1] : gain_[sk], gr = gain_[sk + 1];
      const double gs = std::min(gain_[sk], 0.5 * gain_[sk] + 0.25 * (gl + gr));
      const Cx d = gs * a;
      spec_[sk] = d;
      spec_[nk] = std::conj(d);
      deltaDbSum += 20 * std::log10(1.0 + gs);
      deltaDbCount += 1;
    }
  }
  lastDeltaDb_ = deltaDbCount > 0 ? deltaDbSum / deltaDbCount : 0.0;
  if (!active && lastDeltaDb_ == 0) return;
  if (deltaDbCount == 0) return;
  fft(spec_.data(), true);
  // Synthesis window, overlap-add. sqrt-Hann^2 = Hann; four overlapping Hann frames at hop n/4 sum to 2.
  const size_t base = static_cast<size_t>((pos_ - n) % n + n) % static_cast<size_t>(n);
  for (int i = 0; i < n; ++i) {
    const size_t idx = (base + static_cast<size_t>(i)) % static_cast<size_t>(n);
    acc_[idx] += std::real(spec_[static_cast<size_t>(i)]) * window_[static_cast<size_t>(i)] * 0.5 / 1.0;
  }
}

}  // namespace eqcore

#include "eqcore/analyzer.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr double kTiny = 1e-30;
double db10(double p) { return 10.0 * std::log10(std::max(p, kTiny)); }
}  // namespace

double SourceFeatures::bandCentreHz(int i) {
  // ISO third-octave centres: 25 Hz * 2^(i/3), i = 0..29 (25 Hz .. 20 kHz).
  return 25.0 * std::pow(2.0, i / 3.0);
}

void SourceFeatures::pack(double* out) const {
  out[0] = valid ? 1.0 : 0.0;
  out[1] = seconds;
  out[2] = loudnessLufs;
  out[3] = peakDbfs;
  out[4] = plrDb;
  out[5] = clipsPerSecond;
  out[6] = correlation;
  out[7] = sideToMidDb;
  out[8] = monoLike ? 1.0 : 0.0;
  out[9] = cutoffHz;
  out[10] = tiltDbPerOct;
  out[11] = mudDb;
  out[12] = boomDb;
  out[13] = harshDb;
  out[14] = airDb;
  for (int i = 0; i < kBands; ++i) out[kScalars + i] = bandDb[static_cast<size_t>(i)];
  out[kLegacyPacked] = hasStereoSpectrum ? 1.0 : 0.0;
  for (int i = 0; i < kBands; ++i) {
    out[kLegacyPacked + 1 + i] = midBandDb[static_cast<size_t>(i)];
    out[kLegacyPacked + 1 + kBands + i] = sideBandDb[static_cast<size_t>(i)];
  }
}

SourceFeatures SourceFeatures::unpack(const double* in, int n) {
  SourceFeatures f;
  if (!in || n < kLegacyPacked) return f;
  f.valid = in[0] != 0.0;
  f.seconds = in[1];
  f.loudnessLufs = in[2];
  f.peakDbfs = in[3];
  f.plrDb = in[4];
  f.clipsPerSecond = in[5];
  f.correlation = in[6];
  f.sideToMidDb = in[7];
  f.monoLike = in[8] != 0.0;
  f.cutoffHz = in[9];
  f.tiltDbPerOct = in[10];
  f.mudDb = in[11];
  f.boomDb = in[12];
  f.harshDb = in[13];
  f.airDb = in[14];
  for (int i = 0; i < kBands; ++i) f.bandDb[static_cast<size_t>(i)] = in[kScalars + i];
  if (n >= kPacked) {
    f.hasStereoSpectrum = in[kLegacyPacked] != 0.0;
    for (int i = 0; i < kBands; ++i) {
      f.midBandDb[static_cast<size_t>(i)] = in[kLegacyPacked + 1 + i];
      f.sideBandDb[static_cast<size_t>(i)] = in[kLegacyPacked + 1 + kBands + i];
    }
  }
  return f;
}

void fftInPlace(std::complex<double>* x, int n) {
  for (int i = 1, j = 0; i < n; ++i) {
    int bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) std::swap(x[i], x[j]);
  }
  for (int len = 2; len <= n; len <<= 1) {
    const double ang = -2.0 * kPi / len;
    const std::complex<double> wl(std::cos(ang), std::sin(ang));
    for (int i = 0; i < n; i += len) {
      std::complex<double> w(1.0, 0.0);
      for (int k = 0; k < len / 2; ++k) {
        const auto u = x[i + k];
        const auto v = x[i + k + len / 2] * w;
        x[i + k] = u + v;
        x[i + k + len / 2] = u - v;
        w *= wl;
      }
    }
  }
}

SourceAnalyzer::SourceAnalyzer(double sampleRate, int channels, double averageSeconds)
    : fs_(sampleRate), channels_(std::clamp(channels, 1, 2)) {
  inputFs_ = sampleRate;
  decim_ = sampleRate > 52000.0 ? std::max(1, static_cast<int>(std::lround(sampleRate / 48000.0))) : 1;
  fs_ = sampleRate / decim_;
  if (decim_ > 1) {
    const double q[4] = {0.5097955791, 0.6013448869, 0.8999762231, 2.5629154477};  // 8th-order Butterworth
    for (int ch = 0; ch < 2; ++ch)
      for (int i = 0; i < 4; ++i) aa_[ch][i].setCoeffs(designBiquad({FilterType::LowPass, 0.46 * fs_, 0.0, q[i], true}, sampleRate));
  }
  avgSeconds_ = std::max(0.5, averageSeconds);
  alpha_ = std::clamp((kFft / fs_) / avgSeconds_, 1e-4, 1.0);
  blockLen_ = std::max(1, static_cast<int>(0.4 * fs_));
  peakRelease_ = std::exp(std::log(0.1) / (10.0 * inputFs_));  // -20 dB in 10 s
  // BS.1770 K-weighting, re-derived for any sample rate.
  {
    const double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
    const double k = std::tan(kPi * f0 / fs_);
    const double vh = std::pow(10.0, g / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    const double a0 = 1.0 + k / q + k * k;
    kShelf_ = {(vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
               2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0};
  }
  {
    const double f0 = 38.13547087602444, q = 0.5003270373238773;
    const double k = std::tan(kPi * f0 / fs_);
    const double a0 = 1.0 + k / q + k * k;
    kHigh_ = {1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0};
  }
  window_.resize(kFft);
  for (int i = 0; i < kFft; ++i) window_[static_cast<size_t>(i)] = 0.5 - 0.5 * std::cos(2.0 * kPi * i / kFft);
  ring_.assign(kFft, 0.0);
  sideRing_.assign(kFft, 0.0);
  fft_.assign(kFft, {0.0, 0.0});
  power_.assign(kFft / 2 + 1, 0.0);
  midPower_ = sidePower_ = power_;
}

void SourceAnalyzer::reset() {
  for (auto& ch : aa_) for (auto& f : ch) f.reset();
  phase_ = 0;
  std::fill(&kz_[0][0][0], &kz_[0][0][0] + 16, 0.0);
  blockPos_ = 0;
  blockEnergy_ = gatedEnergy_ = gatedWeight_ = 0.0;
  peak_ = clipEma_ = clipCount_ = lastAbs_ = 0.0;
  ll_ = rr_ = lr_ = mm_ = ss_ = 0.0;
  stLL_ = stRR_ = stLR_ = stMM_ = stSS_ = 0.0;
  std::fill(ring_.begin(), ring_.end(), 0.0);
  std::fill(sideRing_.begin(), sideRing_.end(), 0.0);
  ringPos_ = 0;
  std::fill(power_.begin(), power_.end(), 0.0);
  std::fill(midPower_.begin(), midPower_.end(), 0.0);
  std::fill(sidePower_.begin(), sidePower_.end(), 0.0);
  activeSeconds_ = 0.0;
  windowsSincePublish_ = 0;
  liveValid_.store(false, std::memory_order_release);
  livePresence_.store(0.0, std::memory_order_relaxed);
  liveSizzle_.store(0.0, std::memory_order_relaxed);
  liveCentre_.store(0.0, std::memory_order_relaxed);
  std::lock_guard<std::mutex> g(lock_);
  published_ = SourceFeatures{};
}

double SourceAnalyzer::kWeighted(int ch, double x) {
  double* z = kz_[ch][0];
  double y = kShelf_.b0 * x + kShelf_.b1 * z[0] + kShelf_.b2 * z[1] - kShelf_.a1 * z[2] - kShelf_.a2 * z[3];
  z[1] = z[0];
  z[0] = x;
  z[3] = z[2];
  z[2] = y;
  double* w = kz_[ch][1];
  const double out = kHigh_.b0 * y + kHigh_.b1 * w[0] + kHigh_.b2 * w[1] - kHigh_.a1 * w[2] - kHigh_.a2 * w[3];
  w[1] = w[0];
  w[0] = y;
  w[3] = w[2];
  w[2] = out;
  return out;
}

void SourceAnalyzer::process(const float* in, int frames) {
  const int C = channels_;
  for (int i = 0; i < frames; ++i) {
    const double l0 = in[static_cast<size_t>(i) * C];
    const double r0 = C == 2 ? static_cast<double>(in[static_cast<size_t>(i) * C + 1]) : l0;
    // Peak (slow release) and clipping at the full input rate: two consecutive near-full-scale samples.
    const double a = std::max(std::fabs(l0), std::fabs(r0));
    peak_ = std::max(a, peak_ * peakRelease_);
    if (a >= 0.9995 && lastAbs_ >= 0.9995) clipCount_ += 1.0;
    lastAbs_ = a;
    double l = l0, r = r0;
    if (decim_ > 1) {  // analysis runs at input rate / decim_ (same window seconds as at 48 kHz)
      for (auto& f : aa_[0]) l = f.process(l);
      for (auto& f : aa_[1]) r = f.process(r);
      if (++phase_ < decim_) continue;
      phase_ = 0;
    }
    // Loudness.
    const double kl = kWeighted(0, l);
    const double kr = C == 2 ? kWeighted(1, r) : 0.0;
    blockEnergy_ += kl * kl + kr * kr;
    if (++blockPos_ >= blockLen_) {
      const double ms = blockEnergy_ / blockLen_;
      const double lufs = -0.691 + db10(ms);
      const double current = gatedWeight_ > 0 ? -0.691 + db10(gatedEnergy_ / gatedWeight_) : -70.0;
      if (lufs > -70.0 && (gatedWeight_ <= 0 || lufs > current - 10.0)) {
        const double decay = std::exp(-0.4 / avgSeconds_);
        gatedEnergy_ = gatedEnergy_ * decay + ms;
        gatedWeight_ = gatedWeight_ * decay + 1.0;
      }
      blockEnergy_ = 0.0;
      blockPos_ = 0;
    }
    // Stereo image (accumulated per window, averaged in analyseWindow).
    const double m = 0.5 * (l + r), s = 0.5 * (l - r);
    ll_ += l * l;
    rr_ += r * r;
    lr_ += l * r;
    mm_ += m * m;
    ss_ += s * s;
    ring_[static_cast<size_t>(ringPos_)] = m;
    sideRing_[static_cast<size_t>(ringPos_)] = s;
    if (++ringPos_ >= kFft) {
      ringPos_ = 0;
      analyseWindow();
    }
  }
}

void SourceAnalyzer::analyseWindow() {
  // Window energy decides whether this is music or a pause: pauses must not wash
  // out the long-term picture.
  const double e = mm_ + ss_;
  const double winSeconds = kFft / fs_;
  const double ms = e / kFft;
  const double clipsNow = clipCount_ / winSeconds;
  clipCount_ = 0.0;
  const bool active = ms > 1e-6;  // -60 dBFS mean square across BOTH channels
  if (active) {
    const int nActive = static_cast<int>(activeSeconds_ / winSeconds + 0.5);
    const double a = std::max(alpha_, 1.0 / (nActive + 1.0));  // plain mean until the EMA is full
    // Reuse one preallocated FFT buffer; no allocations on the audio thread.
    for (int channel = 0; channel < 2; ++channel) {
      const auto& input = channel == 0 ? ring_ : sideRing_;
      auto& spectrum = channel == 0 ? midPower_ : sidePower_;
      for (int i = 0; i < kFft; ++i)
        fft_[static_cast<size_t>(i)] = {input[static_cast<size_t>(i)] * window_[static_cast<size_t>(i)], 0.0};
      fftInPlace(fft_.data(), kFft);
      for (int k = 0; k <= kFft / 2; ++k) {
        const auto j = static_cast<size_t>(k);
        spectrum[j] += a * (std::norm(fft_[j]) - spectrum[j]);
      }
    }
    for (size_t k = 0; k < power_.size(); ++k) power_[k] = midPower_[k] + sidePower_[k];
    stLL_ += a * (ll_ - stLL_);
    stRR_ += a * (rr_ - stRR_);
    stLR_ += a * (lr_ - stLR_);
    stMM_ += a * (mm_ - stMM_);
    stSS_ += a * (ss_ - stSS_);
    clipEma_ += a * (clipsNow - clipEma_);
    activeSeconds_ += winSeconds;
  }
  ll_ = rr_ = lr_ = mm_ = ss_ = 0.0;
  if (++windowsSincePublish_ >= 12) {  // ~1 s at 48 kHz
    windowsSincePublish_ = 0;
    publish();
  }
}

void SourceAnalyzer::publish() {
  SourceFeatures f;
  const double binHz = fs_ / kFft;
  f.seconds = activeSeconds_;
  f.valid = activeSeconds_ >= 3.0;
  f.loudnessLufs = gatedWeight_ > 0 ? -0.691 + db10(gatedEnergy_ / gatedWeight_) : -70.0;
  f.peakDbfs = 20.0 * std::log10(std::max(peak_, 1e-6));
  f.plrDb = f.peakDbfs - f.loudnessLufs;
  f.clipsPerSecond = clipEma_;
  const double denom = std::sqrt(std::max(stLL_ * stRR_, kTiny));
  f.correlation = stLL_ > 0 && stRR_ > 0 ? stLR_ / denom : 1.0;
  f.sideToMidDb = db10(stSS_) - db10(stMM_);
  f.monoLike = f.sideToMidDb < -40.0;

  // Lossy ceiling: from the top, the first 8-bin (~94 Hz at 48 kHz) stretch whose
  // energy is within 60 dB of the 1-6 kHz reference.
  double ref = 0.0;
  int nref = 0;
  for (int k = static_cast<int>(1000.0 / binHz); k <= static_cast<int>(6000.0 / binHz) && k <= kFft / 2; ++k) {
    ref += power_[static_cast<size_t>(k)];
    ++nref;
  }
  const double refDb = db10(nref ? ref / nref : 0.0);
  const int top = std::min(kFft / 2, static_cast<int>(20500.0 / binHz));
  f.cutoffHz = 0.0;
  for (int k = top; k >= 8; --k) {
    double s = 0.0;
    for (int j = 0; j < 8; ++j) s += power_[static_cast<size_t>(k - j)];
    if (db10(s / 8.0) >= refDb - 60.0) {
      f.cutoffHz = std::min(20000.0, k * binHz);
      break;
    }
  }

  // Third-octave levels.
  for (int b = 0; b < SourceFeatures::kBands; ++b) {
    const double fc = SourceFeatures::bandCentreHz(b);
    const double lo = fc / std::pow(2.0, 1.0 / 6.0), hi = fc * std::pow(2.0, 1.0 / 6.0);
    auto level = [&](const std::vector<double>& spectrum) {
      double p = 0.0;
      bool hasBin = false;
      for (int k = std::max(1, static_cast<int>(std::ceil(lo / binHz))); k <= kFft / 2 && k * binHz < hi; ++k) {
        p += spectrum[static_cast<size_t>(k)];
        hasBin = true;
      }
      if (!hasBin) p = spectrum[static_cast<size_t>(std::clamp(static_cast<int>(fc / binHz + 0.5), 1, kFft / 2))];
      return db10(p);
    };
    f.bandDb[static_cast<size_t>(b)] = level(power_);
    f.midBandDb[static_cast<size_t>(b)] = level(midPower_);
    f.sideBandDb[static_cast<size_t>(b)] = level(sidePower_);
  }
  f.hasStereoSpectrum = f.valid;
  // Floor empty bands 80 dB under the loudest one: silence in a band (a sparse
  // mix, a test tone) must not drag the tilt line or fake huge deviations.
  double maxBand = -1e9;
  for (double v : f.bandDb) maxBand = std::max(maxBand, v);
  for (double& v : f.bandDb) v = std::max(v, maxBand - 80.0);
  // The mix's own tilt: least squares of level vs octaves over 63 Hz..10 kHz (below the ceiling).
  const double fitTop = f.cutoffHz > 0 ? std::min(10000.0, 0.8 * f.cutoffHz) : 10000.0;
  double sx = 0, sy = 0, sxx = 0, sxy = 0;
  int n = 0;
  for (int b = 0; b < SourceFeatures::kBands; ++b) {
    const double fc = SourceFeatures::bandCentreHz(b);
    if (fc < 63.0 || fc > fitTop) continue;
    const double x = std::log2(fc / 1000.0), y = f.bandDb[static_cast<size_t>(b)];
    sx += x;
    sy += y;
    sxx += x * x;
    sxy += x * y;
    ++n;
  }
  double slope = 0.0, icpt = 0.0;
  if (n >= 3) {
    slope = (n * sxy - sx * sy) / std::max(n * sxx - sx * sx, 1e-12);
    icpt = (sy - slope * sx) / n;
  }
  f.tiltDbPerOct = slope;
  auto residual = [&](double lo, double hi) {
    double s = 0;
    int m = 0;
    for (int b = 0; b < SourceFeatures::kBands; ++b) {
      const double fc = SourceFeatures::bandCentreHz(b);
      if (fc < lo * 0.99 || fc > hi * 1.01) continue;
      if (f.cutoffHz > 0 && fc > 0.9 * f.cutoffHz) continue;
      s += f.bandDb[static_cast<size_t>(b)] - (icpt + slope * std::log2(fc / 1000.0));
      ++m;
    }
    return m ? s / m : 0.0;
  };
  if (n >= 3) {
    f.boomDb = residual(63, 125);
    f.mudDb = residual(200, 500);
    f.harshDb = residual(2500, 5000);
    f.airDb = residual(10000, 16000);
    f.sizzleDb = residual(6000, 10000);
  }
  // Centre dominance over 1-4 kHz: mid power over side power, energy-weighted, so the loudest content in the range
  // decides (empty bands carry no weight). Bounded so silent sides stay finite.
  double midSum = 0.0, sideSum = 0.0;
  for (int b = 0; b < SourceFeatures::kBands; ++b) {
    const double fc = SourceFeatures::bandCentreHz(b);
    if (fc < 990.0 || fc > 4100.0) continue;
    midSum += std::pow(10.0, f.midBandDb[static_cast<size_t>(b)] / 10.0);
    sideSum += std::pow(10.0, f.sideBandDb[static_cast<size_t>(b)] / 10.0);
  }
  liveCentre_.store(midSum > 0 ? std::clamp(db10(midSum) - db10(sideSum), -20.0, 60.0) : 0.0, std::memory_order_relaxed);
  livePresence_.store(f.harshDb, std::memory_order_relaxed);
  liveSizzle_.store(f.sizzleDb, std::memory_order_relaxed);
  liveValid_.store(f.valid && n >= 3, std::memory_order_release);
  if (lock_.try_lock()) {
    published_ = f;
    lock_.unlock();
  }
}

SourceAnalyzer::LiveResiduals SourceAnalyzer::liveResiduals() const {
  LiveResiduals r;
  r.valid = liveValid_.load(std::memory_order_acquire);
  r.presenceDb = livePresence_.load(std::memory_order_relaxed);
  r.sizzleDb = liveSizzle_.load(std::memory_order_relaxed);
  r.centreDb = liveCentre_.load(std::memory_order_relaxed);
  return r;
}

SourceFeatures SourceAnalyzer::snapshot() const {
  std::lock_guard<std::mutex> g(lock_);
  return published_;
}

}  // namespace eqcore

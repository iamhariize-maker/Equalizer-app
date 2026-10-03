#include "eqcore/oversampler.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
  double sum = 1.0, term = 1.0;
  const double q = x * x / 4.0;
  for (int k = 1; k < 200; ++k) {
    term *= q / (static_cast<double>(k) * k);
    sum += term;
    if (term < 1e-18 * sum) break;
  }
  return sum;
}

double sinc(double x) {
  if (std::fabs(x) < 1e-12) return 1.0;
  return std::sin(kPi * x) / (kPi * x);
}

// Low-pass FIR for a 2x stage. Frequencies are fractions of the stage's
// *input* rate; the filter runs at twice that rate.
//  passFrac: passband edge, stopFrac = 1 - passFrac: first aliasing image.
// Length is forced to 2^k * m + 1 so the round-trip latency is an integer
// number of base-rate samples (k = 1-based stage index).
std::vector<double> designStage(double passFrac, double stopbandDb, int stageIndex) {
  const double stopFrac = 1.0 - passFrac;
  const double delta = (stopFrac - passFrac) / 2.0;  // transition width at output rate
  const double fc = (passFrac + stopFrac) / 4.0;      // cutoff at output rate (= 0.25)
  const double A = stopbandDb;
  const double beta = A > 50 ? 0.1102 * (A - 8.7) : (A > 21 ? 0.5842 * std::pow(A - 21, 0.4) + 0.07886 * (A - 21) : 0.0);
  int taps = static_cast<int>(std::ceil((A - 7.95) / (14.36 * delta))) + 1;
  taps = std::max(taps, 7);
  const int mult = 1 << stageIndex;
  taps = ((taps - 1 + mult - 1) / mult) * mult + 1;

  std::vector<double> h(taps);
  const int mid = (taps - 1) / 2;
  const double i0b = besselI0(beta);
  double sum = 0.0;
  for (int n = 0; n < taps; ++n) {
    const double m = n - mid;
    const double r = m / mid;
    const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
    h[n] = 2.0 * fc * sinc(2.0 * fc * m) * w;
    sum += h[n];
  }
  for (auto& v : h) v /= sum;  // unity DC gain
  return h;
}
}  // namespace

Oversampler::Oversampler(const OversamplerSpec& spec) : spec_(spec) {
  int f = spec_.factor;
  if (f != 1 && f != 2 && f != 4 && f != 8) f = 1;
  spec_.factor = f;

  int nStages = 0;
  for (int t = f; t > 1; t >>= 1) ++nStages;

  const double nyq = spec_.baseSampleRate / 2.0;
  const double passHz = std::min(spec_.passbandHz, 0.99 * nyq);
  double latency = 0.0;
  for (int k = 1; k <= nStages; ++k) {
    // Stage k's input rate is fs * 2^(k-1).
    const double inRate = spec_.baseSampleRate * std::pow(2.0, k - 1);
    Stage s;
    s.h = designStage(passHz / inRate, spec_.stopbandDb, k);
    const int taps = static_cast<int>(s.h.size());
    s.upLen = (taps + 1) / 2;
    s.phase0.assign(s.upLen, 0.0);
    s.phase1.assign(s.upLen, 0.0);
    for (int j = 0; j < s.upLen; ++j) {
      if (2 * j < taps) s.phase0[j] = 2.0 * s.h[2 * j];      // x2 compensates zero-stuffing
      if (2 * j + 1 < taps) s.phase1[j] = 2.0 * s.h[2 * j + 1];
    }
    s.upHist.assign(2 * s.upLen, 0.0);
    s.downHist.assign(2 * taps, 0.0);
    latency += (taps - 1) / std::pow(2.0, k);  // up + down group delay in base samples
    stages_.push_back(std::move(s));
  }
  latency_ = static_cast<int>(std::lround(latency));
  const size_t maxHigh = static_cast<size_t>(spec_.maxBlock) * f;
  bufA_.assign(maxHigh, 0.0);
  bufB_.assign(maxHigh, 0.0);
}

int Oversampler::totalTaps() const {
  int t = 0;
  for (const auto& s : stages_) t += static_cast<int>(s.h.size());
  return t;
}

void Oversampler::reset() {
  for (auto& s : stages_) {
    std::fill(s.upHist.begin(), s.upHist.end(), 0.0);
    std::fill(s.downHist.begin(), s.downHist.end(), 0.0);
    s.upPos = s.downPos = 0;
  }
}

void Oversampler::upStage(Stage& s, const double* in, int n, double* out) {
  const int L = s.upLen;
  for (int i = 0; i < n; ++i) {
    // Ring is written twice so the newest L samples are contiguous.
    s.upPos = (s.upPos + L - 1) % L;
    s.upHist[s.upPos] = in[i];
    s.upHist[s.upPos + L] = in[i];
    const double* x = &s.upHist[s.upPos];  // x[0] newest ... x[L-1] oldest
    double a = 0.0, b = 0.0;
    for (int j = 0; j < L; ++j) {
      a += s.phase0[j] * x[j];
      b += s.phase1[j] * x[j];
    }
    out[2 * i] = a;
    out[2 * i + 1] = b;
  }
}

namespace {
inline void push(Oversampler::Stage& s, int N, double v) {
  s.downPos = (s.downPos + N - 1) % N;
  s.downHist[s.downPos] = v;
  s.downHist[s.downPos + N] = v;
}
}  // namespace

void Oversampler::downStage(Stage& s, const double* in, int n, double* out) {
  const int N = static_cast<int>(s.h.size());
  for (int i = 0; i < n; ++i) {
    // Keep the filtered sample at even high-rate positions so the output
    // stays on the same time grid as the interpolator's input.
    push(s, N, in[2 * i]);
    const double* x = &s.downHist[s.downPos];  // x[0] newest
    double acc = 0.0;
    for (int j = 0; j < N; ++j) acc += s.h[j] * x[j];
    out[i] = acc;
    push(s, N, in[2 * i + 1]);
  }
}

void Oversampler::up(const double* in, int n, double* out) {
  if (stages_.empty()) {
    std::copy(in, in + n, out);
    return;
  }
  const double* src = in;
  int len = n;
  const int nStages = static_cast<int>(stages_.size());
  for (int k = 0; k < nStages; ++k) {
    double* dst = (k == nStages - 1) ? out : (k % 2 == 0 ? bufA_.data() : bufB_.data());
    upStage(stages_[k], src, len, dst);
    src = dst;
    len *= 2;
  }
}

void Oversampler::down(const double* in, int n, double* out) {
  if (stages_.empty()) {
    std::copy(in, in + n, out);
    return;
  }
  const double* src = in;
  int len = n * spec_.factor;
  const int nStages = static_cast<int>(stages_.size());
  for (int k = nStages - 1; k >= 0; --k) {
    len /= 2;
    double* dst = (k == 0) ? out : ((nStages - 1 - k) % 2 == 0 ? bufA_.data() : bufB_.data());
    downStage(stages_[k], src, len, dst);
    src = dst;
  }
}

}  // namespace eqcore

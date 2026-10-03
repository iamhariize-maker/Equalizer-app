#include "eqcore/resampler.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;

double besselI0(double x) {
  double sum = 1.0, term = 1.0;
  const double q = x * x / 4.0;
  for (int k = 1; k < 300; ++k) {
    term *= q / (static_cast<double>(k) * k);
    sum += term;
    if (term < 1e-20 * sum) break;
  }
  return sum;
}
}  // namespace

Resampler::Resampler(int inputRate, int outputRate, ResamplerQuality quality)
    : inRate_(inputRate), outRate_(outputRate) {
  if (inputRate <= 0 || outputRate <= 0) throw std::invalid_argument("rates must be positive");
  const int g = std::gcd(inputRate, outputRate);
  L_ = outputRate / g;
  M_ = inputRate / g;
  if (L_ > 2048) throw std::invalid_argument("rate ratio too complex");

  const bool audiophile = quality == ResamplerQuality::Audiophile;
  const double passFrac = audiophile ? 0.95 : 0.86;  // of the lower Nyquist
  const double A = audiophile ? 140.0 : 100.0;

  // Prototype runs at L * inRate. Cut-off between pass edge and lower Nyquist.
  const double nyqMin = 0.5 * std::min(inputRate, outputRate);
  const double protoRate = static_cast<double>(L_) * inputRate;
  const double passHz = passFrac * nyqMin, stopHz = nyqMin;
  const double delta = (stopHz - passHz) / protoRate;
  const double fc = 0.5 * (passHz + stopHz) / protoRate;  // cycles/sample at protoRate
  const double beta = 0.1102 * (A - 8.7);
  const long long nWanted = static_cast<long long>(std::ceil((A - 7.95) / (14.36 * delta))) + 1;
  K_ = static_cast<int>((nWanted + L_ - 1) / L_);
  K_ = std::max(K_, 4);
  const long long N = static_cast<long long>(K_) * L_;

  // h[j], j = 0..N-1, centred at (N-1)/2; gain L compensates zero-insertion.
  auto table = std::make_shared<std::vector<double>>(static_cast<size_t>(N));
  const double mid = (N - 1) / 2.0;
  const double i0b = besselI0(beta);
  std::vector<double> h(static_cast<size_t>(N));
  for (long long j = 0; j < N; ++j) {
    const double m = j - mid;
    const double r = m / (mid + 0.5);
    const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
    const double x = 2.0 * fc * m;
    const double s = std::fabs(x) < 1e-12 ? 1.0 : std::sin(kPi * x) / (kPi * x);
    h[j] = 2.0 * fc * s * w * L_;
  }
  // Normalise each phase to exactly unit DC gain: no phase-dependent ripple
  // ("interpolation noise") for low-frequency content.
  for (int p = 0; p < L_; ++p) {
    double sum = 0.0;
    for (int k = 0; k < K_; ++k) sum += h[static_cast<size_t>(k) * L_ + p];
    for (int k = 0; k < K_; ++k) (*table)[static_cast<size_t>(p) * K_ + k] = h[static_cast<size_t>(k) * L_ + p] / sum;
  }
  table_ = std::move(table);
  hist_.assign(2 * static_cast<size_t>(K_), 0.0);
  reset();
}

double Resampler::latencyInputSamples() const {
  return ((static_cast<double>(K_) * L_) - 1) / 2.0 / L_;
}

int Resampler::maxOutputFor(int nIn) const {
  return static_cast<int>((static_cast<long long>(nIn) * L_ + M_ - 1) / M_) + 1;
}

void Resampler::reset() {
  std::fill(hist_.begin(), hist_.end(), 0.0);
  pos_ = 0;
  phase_ = 0;
  pending_ = 1;  // output 0 needs input 0
}

int Resampler::process(const double* in, int nIn, double* out) {
  const double* table = table_->data();
  int produced = 0;
  for (int i = 0; i < nIn; ++i) {
    pos_ = (pos_ + K_ - 1) % K_;
    hist_[pos_] = in[i];
    hist_[pos_ + K_] = in[i];
    if (--pending_ > 0) continue;
    // Emit every output whose time t = n*M/L falls on or before this input.
    const double* x = &hist_[pos_];  // x[0] newest
    while (pending_ <= 0) {
      const double* c = table + static_cast<size_t>(phase_) * K_;
      double acc = 0.0;
      for (int k = 0; k < K_; ++k) acc += c[k] * x[k];
      out[produced++] = acc;
      phase_ += M_;
      while (phase_ >= L_) {
        phase_ -= L_;
        ++pending_;
      }
    }
  }
  return produced;
}

}  // namespace eqcore

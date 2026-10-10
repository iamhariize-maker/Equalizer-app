// Probe 5: what does the repo's own analyzer say about the scenarios the ShrillGuard ignored?
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "eqcore/analyzer.h"

using namespace eqcore;
static const double kPi = 3.14159265358979323846;

static std::vector<float> noiseTilt(double fs, double secs, double slope, unsigned seed, double hfFlatFrom = 0) {
  // White noise filtered in the frequency domain would be cleanest; use a time-domain approximation:
  // cascade of one-pole low-passes (about -6 dB/oct each above its corner) on white noise, summed with a flat part.
  std::mt19937 rng(seed);
  std::normal_distribution<double> nd(0, 1);
  const size_t n = static_cast<size_t>(fs * secs);
  std::vector<double> w(n);
  for (auto& v : w) v = nd(rng);
  // pink (-3 dB/oct) by Kellet
  double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
  std::vector<double> y(n);
  for (size_t i = 0; i < n; ++i) {
    const double x = w[i];
    b0 = 0.99886 * b0 + x * 0.0555179; b1 = 0.99332 * b1 + x * 0.0750759; b2 = 0.96900 * b2 + x * 0.1538520;
    b3 = 0.86650 * b3 + x * 0.3104856; b4 = 0.55000 * b4 + x * 0.5329522; b5 = -0.7616 * b5 - x * 0.0168980;
    y[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + x * 0.5362) * 0.11; b6 = x * 0.115926;
  }
  if (slope < -3.0) {  // add first-order low-pass stages at 700 Hz and 2.8 kHz (about -3 dB/oct extra each octave-ish)
    for (double fc : {700.0, 2800.0}) {
      const double a = std::exp(-2 * kPi * fc / fs);
      double z = 0;
      for (auto& v : y) { z = (1 - a) * v + a * z; v = z; }
    }
  }
  if (hfFlatFrom > 0) {  // "bright master": add a high-passed noise floor so the top does not fall away
    const double a = std::exp(-2 * kPi * hfFlatFrom / fs);
    double z = 0;
    for (size_t i = 0; i < n; ++i) { z = (1 - a) * w[i] + a * z; y[i] += 0.12 * (w[i] - z); }
  }
  double e = 0;
  for (double v : y) e += v * v;
  const double g = 0.1 / std::sqrt(e / n);
  std::vector<float> out(2 * n);
  for (size_t i = 0; i < n; ++i) out[2 * i] = out[2 * i + 1] = static_cast<float>(y[i] * g);
  return out;
}

static void report(const char* name, const std::vector<float>& in, double fs) {
  SourceAnalyzer an(fs, 2);
  const int blk = 480;
  for (size_t s = 0; s + blk <= in.size() / 2; s += blk) an.process(&in[2 * s], blk);
  const auto f = an.snapshot();
  std::printf("  %-34s tilt %+5.1f dB/oct   harsh %+5.1f dB   air %+5.1f dB   mud %+5.1f   boom %+5.1f\n", name, f.tiltDbPerOct, f.harshDb, f.airDb, f.mudDb, f.boomDb);
}

int main() {
  const double fs = 48000;
  const size_t n = static_cast<size_t>(fs * 5);
  std::printf("SourceAnalyzer residuals (the measure Svaresa already gates on; smoothing starts at harsh > 1.5 dB)\n");
  report("pink noise", noiseTilt(fs, 5, -3.0, 1), fs);
  report("music-like (steeper than pink)", noiseTilt(fs, 5, -6.0, 2), fs);
  report("bright master (flat top added)", noiseTilt(fs, 5, -6.0, 3, 1500.0), fs);
  {
    // Scenario A from probe 4: bass + mids + three sustained presence partials.
    std::vector<float> a(2 * n);
    for (size_t i = 0; i < n; ++i) {
      const double t = i / fs;
      const double v = 0.25 * std::sin(2 * kPi * 55 * t) + 0.08 * std::sin(2 * kPi * 400 * t) + 0.06 * std::sin(2 * kPi * 800 * t) +
                       0.05 * (std::sin(2 * kPi * 3600 * t) + std::sin(2 * kPi * 4200 * t) + std::sin(2 * kPi * 4800 * t));
      a[2 * i] = a[2 * i + 1] = static_cast<float>(v);
    }
    report("shrill cluster (probe 4 scenario A)", a, fs);
  }
  return 0;
}

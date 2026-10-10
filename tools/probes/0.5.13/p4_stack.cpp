// Probe 4: how much does ShrillGuard add on top of the reducers the chain already has?
#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <random>
#include <vector>

#include "eqcore/engine.h"

using namespace eqcore;
static const double kPi = 3.14159265358979323846;
static double db(double v) { return 20 * std::log10(std::max(v, 1e-12)); }

static double amp(const std::vector<float>& x, int ch, double f, double fs, size_t a, size_t b) {
  std::complex<double> acc = 0;
  for (size_t i = a; i < b; ++i) acc += static_cast<double>(x[2 * i + ch]) * std::polar(1.0, -2 * kPi * f * i / fs);
  return 2.0 * std::abs(acc) / (b - a);
}

struct Config { const char* name; double restraint, body, smooth, dyn, guard; };

static std::vector<float> run(const Config& c, const std::vector<float>& in, double fs) {
  auto cfg = EngineConfig::forQuality(QualityMode::Audiophile, fs, 2, 24);
  Engine e(cfg);
  e.setGrounding({c.restraint, c.body});
  e.setStereoTuner({0.3, 0.3, c.smooth, 0.0, 0.3, 0.0, 0.0});
  e.setDynamicEq(c.dyn);
  e.setShrillGuard(c.guard);
  e.setBassTexture(c.body);
  std::vector<float> out(in.size());
  const int blk = 480;
  for (size_t s = 0; s + blk <= in.size() / 2; s += blk) e.process(&in[2 * s], &out[2 * s], blk);
  return out;
}

int main() {
  const double fs = 48000;
  const size_t n = static_cast<size_t>(fs * 5);
  // Scenario A: sustained shrill cluster (three guitar-presence partials) over bass and mids.
  std::vector<float> a(2 * n);
  for (size_t i = 0; i < n; ++i) {
    const double t = i / fs;
    const double v = 0.25 * std::sin(2 * kPi * 55 * t) + 0.08 * std::sin(2 * kPi * 400 * t) + 0.06 * std::sin(2 * kPi * 800 * t) +
                     0.05 * (std::sin(2 * kPi * 3600 * t) + std::sin(2 * kPi * 4200 * t) + std::sin(2 * kPi * 4800 * t));
    a[2 * i] = a[2 * i + 1] = static_cast<float>(v);
  }
  // Scenario B: hi-hat pattern (noise bursts high-passed by differencing) over the same bass, one hit per 125 ms.
  std::vector<float> b(2 * n);
  std::mt19937 rng(5);
  std::normal_distribution<double> nd(0, 1);
  for (size_t i = 0; i < n; ++i) {
    const double t = i / fs;
    const double since = std::fmod(t, 0.125);
    static double prev = 0;
    const double w = nd(rng);
    const double hp = w - prev; prev = w;  // crude high-pass: lots of energy above 6 kHz
    const double v = 0.25 * std::sin(2 * kPi * 55 * t) + 0.12 * std::exp(-since / 0.012) * hp * 0.5;
    b[2 * i] = b[2 * i + 1] = static_cast<float>(v);
  }

  const Config cfgs[] = {
      {"chain off (reference)", 0, 0, 0, 0, 0},
      {"existing reducers only", 0.7, 0.7, 0.5, 1.0, 0.0},
      {"existing + ShrillGuard 0.7", 0.7, 0.7, 0.5, 1.0, 0.7},
      {"ShrillGuard 1.0 alone", 0, 0, 0, 0, 1.0},
  };
  std::printf("A. sustained shrill cluster: level change of the three partials vs reference-in (dB)\n");
  for (const auto& c : cfgs) {
    auto o = run(c, a, fs);
    std::printf("  %-30s 3.6k %+6.2f   4.2k %+6.2f   4.8k %+6.2f   |  400 Hz %+5.2f   55 Hz %+5.2f\n", c.name,
                db(amp(o, 0, 3600, fs, 96000, n)) - db(0.05), db(amp(o, 0, 4200, fs, 96000, n)) - db(0.05),
                db(amp(o, 0, 4800, fs, 96000, n)) - db(0.05), db(amp(o, 0, 400, fs, 96000, n)) - db(0.08),
                db(amp(o, 0, 55, fs, 96000, n)) - db(0.25));
  }
  std::printf("B. hi-hat hits: mean peak of the HF part of each hit (differenced output), change vs chain-off (dB)\n");
  double ref = 0;
  for (const auto& c : cfgs) {
    auto o = run(c, b, fs);
    double sum = 0; int cnt = 0;
    for (double t0 = 1.0; t0 < 4.8; t0 += 0.125) {
      const size_t s0 = static_cast<size_t>(t0 * fs), s1 = s0 + static_cast<size_t>(0.012 * fs);
      double pk = 0;
      for (size_t i = s0 + 1; i < s1; ++i) pk = std::max(pk, std::fabs(static_cast<double>(o[2 * i]) - o[2 * (i - 1)]));
      sum += pk; ++cnt;
    }
    const double mean = sum / cnt;
    if (ref == 0) ref = mean;
    std::printf("  %-30s %+6.2f dB\n", c.name, db(mean) - db(ref));
  }
  return 0;
}

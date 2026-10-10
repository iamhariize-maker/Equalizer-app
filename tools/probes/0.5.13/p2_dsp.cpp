// Probe 2: do the new stages behave on realistic material, not only on pure tones?
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "eqcore/biquad.h"
#include "eqcore/bass_texture.h"
#include "eqcore/engine.h"
#include "eqcore/shrill_guard.h"

using namespace eqcore;
static const double kPi = 3.14159265358979323846;

// Noise with a spectral tilt of `slopeDbOct` (dB per octave) above 100 Hz, by filtering white noise with a cascade of
// first-order shelving sections (cheap, good enough for a balance probe).
static std::vector<double> tiltedNoise(double fs, double seconds, double slopeDbOct, unsigned seed) {
  std::mt19937 rng(seed);
  std::normal_distribution<double> nd(0, 1);
  const int n = static_cast<int>(fs * seconds);
  std::vector<double> x(n);
  for (auto& v : x) v = nd(rng);
  // Pink-ish via Paul Kellet's filter gives -3 dB/oct; extra tilt from a one-pole low-pass cascade.
  double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
  std::vector<double> y(n);
  for (int i = 0; i < n; ++i) {
    const double w = x[i];
    b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520;
    b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980;
    y[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
    b6 = w * 0.115926;
  }
  if (slopeDbOct < -3.0) {
    // extra (slope + 3) dB/oct: a bank of one-pole low-passes, each adds about -3 dB/oct through its band
    const double extra = (-3.0 - slopeDbOct) / 3.0;  // number of first-order low-pass stages (each ~ -6 dB/oct above its corner)
    const int stages = static_cast<int>(std::lround(extra * 0.5));
    const double fc[] = {600.0, 1400.0, 3000.0, 6000.0};
    for (int s = 0; s < std::min(stages, 4); ++s) {
      const double a = std::exp(-2 * kPi * fc[s] / fs);
      double z = 0;
      for (auto& v : y) { z = (1 - a) * v + a * z; v = z; }
    }
  }
  return y;
}

static double rms(const std::vector<double>& x, size_t a, size_t b) {
  double e = 0;
  for (size_t i = a; i < b; ++i) e += x[i] * x[i];
  return std::sqrt(e / (b - a));
}

static double bandRmsDb(const std::vector<double>& x, double fs, double f0, double q, size_t a, size_t b) {
  auto c = designBiquad({FilterType::BandPass, f0, 0.0, q, true}, fs);
  double z1 = 0, z2 = 0, e = 0;
  for (size_t i = 0; i < x.size(); ++i) {
    const double y = c.b0 * x[i] + z1;
    z1 = c.b1 * x[i] - c.a1 * y + z2;
    z2 = c.b2 * x[i] - c.a2 * y;
    if (i >= a && i < b) e += y * y;
  }
  return 10 * std::log10(e / (b - a) + 1e-30);
}

static void guardOnNoise(const char* name, std::vector<double> x, double fs) {
  // normalise to -20 dBFS rms
  const double g = 0.1 / rms(x, 0, x.size());
  for (auto& v : x) v *= g;
  ShrillGuard guard(fs);
  guard.setDepth(1.0);
  auto y = x;
  double sumP = 0, sumS = 0, minP = 0, minS = 0;
  int cnt = 0;
  for (size_t s = 0; s + 256 <= y.size(); s += 256) {
    guard.process(&y[s], &y[s], 256);
    if (s > static_cast<size_t>(fs)) {  // after 1 s settle
      auto r = guard.reductionsDb();
      sumP += r[0]; sumS += r[1]; minP = std::min(minP, r[0]); minS = std::min(minS, r[1]); ++cnt;
    }
  }
  std::printf("  %-34s presence mean %+.2f dB (min %+.2f)  sizzle mean %+.2f dB (min %+.2f)  band4k %+.1f dB  band8k %+.1f dB re full\n",
              name, sumP / cnt, minP, sumS / cnt, minS,
              bandRmsDb(x, fs, 4000, 1.0, 48000, x.size()) - 20 * std::log10(0.1) * 0 - 10 * std::log10(0.01),
              bandRmsDb(x, fs, 8000, 1.4, 48000, x.size()) - 10 * std::log10(0.01));
}

int main() {
  const double fs = 48000;
  std::printf("A. Does ShrillGuard act on ordinary broadband material at depth 1? (reduction means after 1 s)\n");
  {
    std::mt19937 rng(7);
    std::normal_distribution<double> nd(0, 1);
    std::vector<double> w(static_cast<size_t>(fs * 6));
    for (auto& v : w) v = nd(rng);
    guardOnNoise("white noise (raw)", w, fs);
  }
  guardOnNoise("pink noise (-3 dB/oct, Kellet)", tiltedNoise(fs, 6, -3.0, 2), fs);  // slope arg only changes the filter below -3
  guardOnNoise("music-like (-6 dB/oct)", tiltedNoise(fs, 6, -6.0, 3), fs);
  guardOnNoise("steeper (-9 dB/oct)", tiltedNoise(fs, 6, -9.0, 4), fs);
  return 0;
}

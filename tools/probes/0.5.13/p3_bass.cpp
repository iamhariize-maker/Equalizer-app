// Probe 3: BassTexture on a bass line (decaying notes with partials) and an 808-style glide.
#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdio>
#include <vector>

#include "eqcore/bass_texture.h"

using namespace eqcore;
static const double kPi = 3.14159265358979323846;

static double goertzelAmp(const std::vector<double>& x, double f, double fs, size_t a, size_t b) {
  std::complex<double> acc = 0;
  for (size_t i = a; i < b; ++i) acc += x[i] * std::polar(1.0, -2 * kPi * f * i / fs);
  return 2.0 * std::abs(acc) / (b - a);
}
static double db(double v) { return 20 * std::log10(std::max(v, 1e-12)); }

struct Note { double f0, t0, tau, amp; };

int main() {
  const double fs = 48000;
  const double secs = 4.0;
  const size_t n = static_cast<size_t>(fs * secs);
  std::vector<Note> notes = {{41.2, 0.0, 0.45, 0.5}, {55.0, 0.5, 0.45, 0.5}, {73.4, 1.0, 0.45, 0.5}, {98.0, 1.5, 0.45, 0.5},
                             {41.2, 2.0, 0.9, 0.5}, {49.0, 3.0, 0.9, 0.5}};
  std::vector<double> in(n, 0.0);
  for (const auto& nt : notes) {
    for (size_t i = static_cast<size_t>(nt.t0 * fs); i < n; ++i) {
      const double t = i / fs - nt.t0;
      const double env = (1 - std::exp(-t / 0.004)) * std::exp(-t / nt.tau);
      in[i] += nt.amp * env * (std::sin(2 * kPi * nt.f0 * t) + 0.5 * std::sin(2 * kPi * 2 * nt.f0 * t + 0.3) + 0.25 * std::sin(2 * kPi * 3 * nt.f0 * t + 0.7));
    }
  }
  double peakIn = 0;
  for (double v : in) peakIn = std::max(peakIn, std::fabs(v));
  for (auto& v : in) v *= 0.5 / peakIn;  // peak -6 dBFS
  peakIn = 0.5;

  for (double depth : {0.35, 0.7, 1.0}) {
    BassTexture tex(fs);
    tex.setDepth(depth);
    std::vector<double> l = in, r = in;
    for (size_t s = 0; s < n; s += 256) tex.process(&l[s], &r[s], static_cast<int>(std::min<size_t>(256, n - s)));
    double peakOut = 0, eIn = 0, eWet = 0;
    for (size_t i = 0; i < n; ++i) { peakOut = std::max(peakOut, std::fabs(l[i])); eIn += in[i] * in[i]; eWet += (l[i] - in[i]) * (l[i] - in[i]); }
    // Fundamental deviation per note over its first 150 ms and 150-600 ms (sustained part).
    double worst = 0;
    for (const auto& nt : notes) {
      for (double w0 : {0.01, 0.15, 0.30}) {
        const size_t a = static_cast<size_t>((nt.t0 + w0) * fs), b = a + static_cast<size_t>(0.1 * fs);
        if (b >= n) continue;
        const double d = db(goertzelAmp(l, nt.f0, fs, a, b)) - db(goertzelAmp(in, nt.f0, fs, a, b));
        if (std::fabs(d) > std::fabs(worst)) worst = d;
      }
    }
    std::printf("depth %.2f: peak out/in %+.2f dB   wet rms %+.1f dB re input   worst fundamental deviation %+.3f dB\n",
                depth, db(peakOut / peakIn), 10 * std::log10(eWet / eIn), worst);
  }

  // 808-style: 45 Hz gliding to 35 Hz, level -3 dBFS, 1.5 s.
  {
    const size_t m = static_cast<size_t>(fs * 1.5);
    std::vector<double> x(m);
    double ph = 0;
    for (size_t i = 0; i < m; ++i) {
      const double t = i / fs;
      const double f = 35 + 10 * std::exp(-t / 0.12);
      ph += 2 * kPi * f / fs;
      x[i] = 0.7 * std::exp(-t / 1.0) * std::sin(ph);
    }
    BassTexture tex(fs);
    tex.setDepth(1.0);
    std::vector<double> l = x, r = x;
    for (size_t s = 0; s < m; s += 256) tex.process(&l[s], &r[s], static_cast<int>(std::min<size_t>(256, m - s)));
    double pk = 0, pko = 0, dc = 0;
    for (size_t i = 0; i < m; ++i) { pk = std::max(pk, std::fabs(x[i])); pko = std::max(pko, std::fabs(l[i])); dc += l[i] - x[i]; }
    std::printf("808 glide: peak out/in %+.2f dB, mean offset %.2e\n", db(pko / pk), dc / m);
  }
  return 0;
}

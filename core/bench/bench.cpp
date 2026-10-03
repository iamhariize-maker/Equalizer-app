// CPU cost of each quality mode with an 80-band stereo curve.
// Run on the target phone (adb push + run) for meaningful numbers; host
// numbers are only useful for comparing modes against each other.
#include <chrono>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "eqcore/engine.h"
#include "eqcore/resampler.h"

using namespace eqcore;

int main() {
  const double fs = 48000;
  const int seconds = 10, block = 256;
  std::vector<BandParams> bands;
  for (int i = 0; i < 80; ++i)
    bands.push_back({FilterType::Peak, 20.0 * std::pow(1000.0, i / 79.0), (i % 3) - 1.0, 2.0, true});

  std::mt19937 rng(1);
  std::uniform_real_distribution<float> u(-0.5f, 0.5f);
  std::vector<float> buf(block * 2);

  const struct { const char* name; QualityMode mode; } modes[] = {
      {"Efficient  (1x)", QualityMode::Efficient},
      {"HighQuality(2x)", QualityMode::HighQuality},
      {"Audiophile (4x)", QualityMode::Audiophile},
      {"Extreme    (8x)", QualityMode::Extreme},
  };
  std::printf("80-band stereo EQ, %g Hz, %d s of audio per mode\n", fs, seconds);
  for (const auto& m : modes) {
    Engine e(EngineConfig::forQuality(m.mode, fs, 2, 24));
    e.setBandsAllChannels(bands);
    const long frames = static_cast<long>(fs) * seconds;
    const auto t0 = std::chrono::steady_clock::now();
    for (long done = 0; done < frames; done += block) {
      for (auto& v : buf) v = u(rng);
      e.process(buf.data(), buf.data(), block);
    }
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("  %s  %7.1fx realtime  %5.2f%% of one core  latency %4d frames (%.2f ms)\n",
                m.name, seconds / secs, 100.0 * secs / seconds, e.latencyFrames(),
                1000.0 * e.latencyFrames() / fs);
  }

  std::printf("\nResampler, stereo, %d s of input per setting\n", seconds);
  const struct { int in, out; } pairs[] = {{44100, 48000}, {44100, 96000}, {48000, 192000}};
  for (const auto& p : pairs) {
    for (auto q : {ResamplerQuality::Quality, ResamplerQuality::Audiophile}) {
      Resampler l(p.in, p.out, q), r(p.in, p.out, q);
      std::vector<double> in(block), out(l.maxOutputFor(block));
      const long frames = static_cast<long>(p.in) * seconds;
      const auto t0 = std::chrono::steady_clock::now();
      for (long done = 0; done < frames; done += block) {
        for (auto& v : in) v = u(rng);
        l.process(in.data(), block, out.data());
        r.process(in.data(), block, out.data());
      }
      const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
      std::printf("  %6d -> %6d  %-10s  %4d taps/phase  %6.1fx realtime  %5.2f%% of one core\n", p.in, p.out,
                  q == ResamplerQuality::Audiophile ? "Audiophile" : "Quality", l.tapsPerPhase(), seconds / secs,
                  100.0 * secs / seconds);
    }
  }
  return 0;
}

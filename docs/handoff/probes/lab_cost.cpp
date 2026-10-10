// Incremental CPU cost of each Lab and Svaresa switch on top of a plain engine.
// Host numbers only compare configurations with each other; the phone needs its own run.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "eqcore/engine.h"

using namespace eqcore;

namespace {

struct Switches {
  const char* name;
  bool protection, headroom, dynamicEq, analysis;
};

// Percent of one host core for 10 s of stereo 48 kHz audio; the best of two runs damps scheduler noise.
double percentOfCore(QualityMode mode, const Switches& s, const std::vector<BandParams>& bands) {
  const double fs = 48000, seconds = 10;
  const int block = 256;
  double best = 1e9;
  for (int run = 0; run < 2; ++run) {
    Engine e(EngineConfig::forQuality(mode, fs, 2, 24));
    e.setBandsAllChannels(bands);
    e.setGainProtection(s.protection);
    e.setAutoHeadroom(s.headroom);
    e.setDynamicEq(s.dynamicEq ? 1.0 : 0.0);
    e.setAnalysisEnabled(s.analysis);
    std::mt19937 rng(7);
    std::uniform_real_distribution<float> u(-0.5f, 0.5f);
    std::vector<float> buf(block * 2);
    const long frames = static_cast<long>(fs * seconds);
    const auto t0 = std::chrono::steady_clock::now();
    for (long done = 0; done < frames; done += block) {
      for (auto& v : buf) v = u(rng);
      e.process(buf.data(), buf.data(), block);
    }
    const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    best = std::min(best, 100.0 * secs / seconds);
  }
  return best;
}

}  // namespace

int main() {
  // Twelve peaking bands with a moderate, Svaresa-like curve.
  std::vector<BandParams> bands;
  for (int i = 0; i < 12; ++i) bands.push_back({FilterType::Peak, 60.0 * std::pow(1.6, i), (i % 3) - 1.0, 1.4, true});

  const Switches switches[] = {
      {"engine only", false, false, false, false},
      {"+ gain protection", true, false, false, false},
      {"+ auto headroom", false, true, false, false},
      {"+ selective dynamic EQ", false, false, true, false},
      {"+ analysis (Engine B hears)", false, false, false, true},
      {"Svaresa full set", true, true, true, true},
  };
  const struct { const char* name; QualityMode mode; } modes[] = {
      {"Efficient (1x)", QualityMode::Efficient},
      {"Audiophile (4x)", QualityMode::Audiophile},
  };
  for (const auto& m : modes) {
    std::printf("%s, 12 bands stereo 48 kHz, 10 s per setting\n", m.name);
    double base = 0.0;
    for (const auto& s : switches) {
      const double pct = percentOfCore(m.mode, s, bands);
      if (s.protection == false && s.headroom == false && s.dynamicEq == false && s.analysis == false) base = pct;
      std::printf("  %-28s %6.2f%% of one core   %+6.2f vs engine only\n", s.name, pct, pct - base);
    }
    std::printf("\n");
  }
  return 0;
}

// Probe 1: what does a source change cost on the audio thread, and what do the new stages cost per second of audio?
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <random>
#include <vector>

#include "eqcore/engine.h"
#include "eqcore/bass_texture.h"
#include "eqcore/shrill_guard.h"

using namespace eqcore;
using Clock = std::chrono::steady_clock;

static double ms(Clock::time_point a, Clock::time_point b) {
  return std::chrono::duration<double, std::milli>(b - a).count();
}

static void construction(const char* name, QualityMode q, double fs, bool detailed) {
  std::vector<double> t;
  for (int i = 0; i < 30; ++i) {
    auto cfg = EngineConfig::forQuality(q, fs, 2, 24);
    cfg.spatialResidual = detailed;
    auto a = Clock::now();
    auto e = std::make_unique<Engine>(cfg);
    auto b = Clock::now();
    t.push_back(ms(a, b));
  }
  std::sort(t.begin(), t.end());
  std::printf("  construct %-26s median %6.2f ms  p90 %6.2f ms  max %6.2f ms\n", name, t[t.size() / 2], t[27], t.back());
}

static void resetCost(const char* name, QualityMode q, double fs, bool detailed) {
  auto cfg = EngineConfig::forQuality(q, fs, 2, 24);
  cfg.spatialResidual = detailed;
  Engine e(cfg);
  std::vector<double> t;
  for (int i = 0; i < 30; ++i) {
    auto a = Clock::now();
    e.reset();
    auto b = Clock::now();
    t.push_back(ms(a, b));
  }
  std::sort(t.begin(), t.end());
  std::printf("  reset     %-26s median %6.3f ms  max %6.3f ms\n", name, t[t.size() / 2], t.back());
}

// Real-time factor: ms of CPU per second of stereo audio.
static double rtf(Engine& e, double fs, int seconds) {
  std::mt19937 rng(1);
  std::normal_distribution<float> nd(0.f, 0.1f);
  const int block = 256;
  std::vector<float> in(block * 2), out(block * 2);
  for (auto& v : in) v = nd(rng);
  const int blocks = static_cast<int>(fs * seconds / block);
  auto a = Clock::now();
  for (int i = 0; i < blocks; ++i) e.process(in.data(), out.data(), block);
  auto b = Clock::now();
  return ms(a, b) / seconds;
}

int main() {
  std::printf("Engine construction (what CaptureService.changeSources does on the audio thread)\n");
  construction("Efficient 48k", QualityMode::Efficient, 48000, false);
  construction("High 48k", QualityMode::HighQuality, 48000, false);
  construction("Audiophile 48k", QualityMode::Audiophile, 48000, false);
  construction("Audiophile 48k detailed", QualityMode::Audiophile, 48000, true);
  construction("Audiophile 44.1k", QualityMode::Audiophile, 44100, false);
  construction("Extreme 48k detailed", QualityMode::Extreme, 48000, true);
  std::printf("Engine::reset()\n");
  resetCost("Audiophile 48k", QualityMode::Audiophile, 48000, false);
  resetCost("Audiophile 48k detailed", QualityMode::Audiophile, 48000, true);
  resetCost("Extreme 48k detailed", QualityMode::Extreme, 48000, true);

  std::printf("Per-second CPU of the new stages alone (ms per second of 48k stereo)\n");
  {
    BassTexture t(48000);
    t.setDepth(1.0);
    std::vector<double> l(256, 0.1), r(256, 0.1);
    std::mt19937 rng(2);
    std::normal_distribution<double> nd(0, 0.1);
    for (auto& v : l) v = nd(rng);
    r = l;
    auto a = Clock::now();
    for (int i = 0; i < 48000 / 256 * 5; ++i) t.process(l.data(), r.data(), 256);
    auto b = Clock::now();
    std::printf("  BassTexture   %6.3f ms/s\n", ms(a, b) / 5);
  }
  {
    ShrillGuard g(48000);
    g.setDepth(1.0);
    std::vector<double> l(256), r(256);
    std::mt19937 rng(3);
    std::normal_distribution<double> nd(0, 0.1);
    for (auto& v : l) v = nd(rng);
    r = l;
    auto a = Clock::now();
    for (int i = 0; i < 48000 / 256 * 5; ++i) g.process(l.data(), r.data(), 256);
    auto b = Clock::now();
    std::printf("  ShrillGuard   %6.3f ms/s\n", ms(a, b) / 5);
  }
  for (QualityMode q : {QualityMode::Efficient, QualityMode::Audiophile}) {
    auto cfg = EngineConfig::forQuality(q, 48000, 2, 24);
    Engine plain(cfg);
    Engine full(cfg);
    full.setBassTexture(1.0);
    full.setShrillGuard(1.0);
    std::printf("  Engine %-10s without %6.2f ms/s   with texture+guard %6.2f ms/s\n",
                q == QualityMode::Efficient ? "Efficient" : "Audiophile", rtf(plain, 48000, 4), rtf(full, 48000, 4));
  }
  return 0;
}

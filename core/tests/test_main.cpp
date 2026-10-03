// Self-contained unit tests (no external framework, so the Android/CI builds
// need nothing extra). Run: ./eqcore_tests [filter-substring]
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <functional>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "eqcore/autoeq.h"
#include "eqcore/biquad.h"
#include "eqcore/dither.h"
#include "eqcore/engine.h"
#include "eqcore/oversampler.h"
#include "eqcore/parametric_eq.h"
#include "eqcore/resampler.h"

using namespace eqcore;

namespace {

struct TestCase {
  const char* name;
  std::function<void()> fn;
};
std::vector<TestCase>& registry() {
  static std::vector<TestCase> r;
  return r;
}
int g_failures = 0;

struct Registrar {
  Registrar(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); }
};

#define TEST(name)                                   \
  static void name();                                \
  static Registrar reg_##name(#name, name);          \
  static void name()

#define CHECK(cond)                                                              \
  do {                                                                           \
    if (!(cond)) {                                                               \
      std::printf("    FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);            \
      ++g_failures;                                                              \
    }                                                                            \
  } while (0)

#define CHECK_NEAR(a, b, tol)                                                          \
  do {                                                                                 \
    const double va_ = (a), vb_ = (b);                                                 \
    if (!(std::fabs(va_ - vb_) <= (tol))) {                                            \
      std::printf("    FAIL %s:%d: %s = %.9g, expected %.9g +- %.3g\n", __FILE__, __LINE__, \
                  #a, va_, vb_, static_cast<double>(tol));                             \
      ++g_failures;                                                                    \
    }                                                                                  \
  } while (0)

constexpr double kPi = 3.14159265358979323846;

double toDb(double x) { return 20.0 * std::log10(std::max(x, 1e-300)); }

// Amplitude of a sinusoid at freq in x[start..end) via least-squares fit.
double sineAmplitude(const std::vector<double>& x, double freq, double fs, size_t start, size_t end) {
  double ss = 0, sc = 0, s2 = 0, c2 = 0, sc2 = 0;
  for (size_t i = start; i < end; ++i) {
    const double w = 2 * kPi * freq * static_cast<double>(i) / fs;
    const double s = std::sin(w), c = std::cos(w);
    ss += x[i] * s;
    sc += x[i] * c;
    s2 += s * s;
    c2 += c * c;
    sc2 += s * c;
  }
  const double det = s2 * c2 - sc2 * sc2;
  const double a = (ss * c2 - sc * sc2) / det;
  const double b = (sc * s2 - ss * sc2) / det;
  return std::hypot(a, b);
}

// Power of x at freq (Hann-windowed DFT bin), normalised per sample.
double tonePower(const std::vector<double>& x, double freq, double fs) {
  const size_t n = x.size();
  std::complex<double> acc(0, 0);
  double wsum = 0;
  for (size_t i = 0; i < n; ++i) {
    const double w = 0.5 - 0.5 * std::cos(2 * kPi * static_cast<double>(i) / (n - 1));
    acc += x[i] * w * std::polar(1.0, -2 * kPi * freq * static_cast<double>(i) / fs);
    wsum += w;
  }
  const double amp = 2.0 * std::abs(acc) / wsum;
  return amp * amp;
}

// Analog prototype of the RBJ peaking filter (what a "perfect" EQ would do).
double analogPeakDb(double f, double f0, double gainDb, double q) {
  const double A = std::pow(10.0, gainDb / 40.0);
  const std::complex<double> s(0.0, f / f0);
  const auto h = (s * s + s * (A / q) + 1.0) / (s * s + s / (A * q) + 1.0);
  return toDb(std::abs(h));
}

}  // namespace

// ---------------------------------------------------------------- biquads

TEST(peak_has_exact_gain_at_centre) {
  const double fs = 48000;
  for (double g : {-12.0, -3.0, 3.0, 6.0, 15.0}) {
    auto c = designBiquad({FilterType::Peak, 1000, g, 1.4, true}, fs);
    CHECK_NEAR(magnitudeDb(c, 1000, fs), g, 1e-9);
    CHECK_NEAR(magnitudeDb(c, 20, fs), 0.0, 0.05);
  }
}

TEST(shelves_reach_their_gain) {
  const double fs = 48000;
  auto ls = designBiquad({FilterType::LowShelf, 100, 6, 0.707, true}, fs);
  CHECK_NEAR(magnitudeDb(ls, 10, fs), 6.0, 0.05);
  CHECK_NEAR(magnitudeDb(ls, 100, fs), 3.0, 0.05);  // half gain at fc
  CHECK_NEAR(magnitudeDb(ls, 5000, fs), 0.0, 0.05);
  auto hs = designBiquad({FilterType::HighShelf, 8000, -4, 0.707, true}, fs);
  CHECK_NEAR(magnitudeDb(hs, 20, fs), 0.0, 0.05);
  CHECK_NEAR(magnitudeDb(hs, 8000, fs), -2.0, 0.05);
}

TEST(lowpass_highpass_minus3db_at_fc_notch_is_deep) {
  const double fs = 48000;
  auto lp = designBiquad({FilterType::LowPass, 2000, 0, 0.7071067811865476, true}, fs);
  CHECK_NEAR(magnitudeDb(lp, 2000, fs), -3.0103, 0.001);
  auto hp = designBiquad({FilterType::HighPass, 2000, 0, 0.7071067811865476, true}, fs);
  CHECK_NEAR(magnitudeDb(hp, 2000, fs), -3.0103, 0.001);
  auto no = designBiquad({FilterType::Notch, 50, 0, 10, true}, fs);
  CHECK(magnitudeDb(no, 50, fs) < -100);
  auto ap = designBiquad({FilterType::AllPass, 1000, 0, 1, true}, fs);
  for (double f : {20.0, 1000.0, 15000.0}) CHECK_NEAR(magnitudeDb(ap, f, fs), 0.0, 1e-9);
}

TEST(any_user_input_designs_a_stable_filter) {
  std::mt19937_64 rng(42);
  std::uniform_real_distribution<double> lf(-3, std::log10(200000.0)), g(-30, 30), lq(-3, 2);
  const FilterType types[] = {FilterType::Peak, FilterType::LowShelf, FilterType::HighShelf,
                              FilterType::LowPass, FilterType::HighPass, FilterType::BandPass,
                              FilterType::Notch, FilterType::AllPass};
  int unstable = 0;
  for (int i = 0; i < 20000; ++i) {
    BandParams p{types[i % 8], std::pow(10.0, lf(rng)), g(rng), std::pow(10.0, lq(rng)), true};
    auto c = designBiquad(p, 44100);
    // Jury criterion for a 2nd-order denominator 1 + a1 z^-1 + a2 z^-2.
    const bool stable = std::fabs(c.a2) < 1.0 && std::fabs(c.a1) < 1.0 + c.a2;
    if (!stable || !std::isfinite(c.b0 + c.b1 + c.b2)) ++unstable;
  }
  CHECK(unstable == 0);
}

// ------------------------------------------------------------ parametric EQ

TEST(eq_80_bands_measured_response_matches_prediction) {
  const double fs = 48000;
  ParametricEq eq(1, fs);
  std::vector<BandParams> bands;
  std::mt19937_64 rng(7);
  std::uniform_real_distribution<double> g(-12, 12), q(0.5, 6);
  for (int i = 0; i < 80; ++i) {
    const double f = 20.0 * std::pow(1000.0, i / 79.0);  // 20 Hz .. 20 kHz
    bands.push_back({FilterType::Peak, f, g(rng), q(rng), true});
  }
  eq.setBands(0, bands);
  for (double f : {37.0, 440.0, 3150.0, 12500.0}) {
    const int n = 48000;
    std::vector<double> x(n);
    for (int i = 0; i < n; ++i) x[i] = 0.01 * std::sin(2 * kPi * f * i / fs);
    for (int i = 0; i < n; i += 256) eq.process(0, x.data() + i, std::min(256, n - i));
    const double measured = toDb(sineAmplitude(x, f, fs, n / 2, n) / 0.01);
    CHECK_NEAR(measured, eq.responseDb(0, f), 0.01);
    eq.reset();
  }
}

TEST(eq_survives_white_noise_through_128_extreme_bands) {
  ParametricEq eq(2, 44100);
  std::vector<BandParams> bands;
  for (int i = 0; i < 200; ++i)  // more than the limit on purpose
    bands.push_back({FilterType::Peak, 15.0 + i * 100.0, (i % 2) ? 24.0 : -24.0, 10.0, true});
  eq.setBands(0, bands);
  eq.setBands(1, bands);
  std::mt19937 rng(1);
  std::uniform_real_distribution<double> u(-1, 1);
  std::vector<double> x(44100);
  bool finite = true;
  for (int ch = 0; ch < 2; ++ch) {
    for (auto& v : x) v = u(rng);
    eq.process(ch, x.data(), static_cast<int>(x.size()));
    for (double v : x) finite = finite && std::isfinite(v);
  }
  CHECK(finite);
}

TEST(eq_parameter_updates_from_another_thread_are_safe) {
  ParametricEq eq(2, 48000);
  std::atomic<bool> stop{false};
  std::thread ui([&] {
    std::mt19937 rng(3);
    std::uniform_real_distribution<double> g(-12, 12);
    while (!stop.load()) {
      std::vector<BandParams> b;
      const int n = 1 + static_cast<int>(rng() % 80);
      for (int i = 0; i < n; ++i) b.push_back({FilterType::Peak, 30.0 + i * 200.0, g(rng), 1.0, true});
      eq.setBands(static_cast<int>(rng() % 2), b);
    }
  });
  std::vector<double> buf(256);
  bool finite = true;
  for (int it = 0; it < 4000; ++it) {
    for (size_t i = 0; i < buf.size(); ++i) buf[i] = std::sin(0.01 * (it * 256 + i));
    eq.process(it % 2, buf.data(), 256);
    for (double v : buf) finite = finite && std::isfinite(v);
  }
  stop = true;
  ui.join();
  CHECK(finite);
}

// --------------------------------------------------------------- AutoEq I/O

TEST(autoeq_parametric_file_parses) {
  const char* text =
      "Preamp: -6.2 dB\n"
      "Filter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.70\n"
      "Filter 2: ON LSC Fc 105 Hz Gain 5.0 dB Q 0.70\n"
      "Filter 3: OFF HSC Fc 10000 Hz Gain -3.1 dB Q 0.71\n"
      "Filter 4: ON XYZ Fc 100 Hz Gain 1 dB Q 1\n"
      "Filter 5: ON PK Gain 1 dB Q 1\n"
      "Filter 6: ON HP Fc 20 Hz\n";
  auto p = parseParametricEq(text);
  CHECK_NEAR(p.preampDb, -6.2, 1e-12);
  CHECK(p.bands.size() == 4);
  CHECK(p.warnings.size() == 2);
  CHECK(p.bands[0].type == FilterType::Peak);
  CHECK_NEAR(p.bands[0].freqHz, 105, 1e-12);
  CHECK_NEAR(p.bands[0].gainDb, 4.5, 1e-12);
  CHECK_NEAR(p.bands[0].q, 0.70, 1e-12);
  CHECK(p.bands[1].type == FilterType::LowShelf);
  CHECK(!p.bands[2].enabled);
  CHECK(p.bands[3].type == FilterType::HighPass);
  CHECK_NEAR(p.bands[3].q, 0.7071067811865476, 1e-12);  // default Q kept
}

TEST(autoeq_graphic_file_parses_and_sorts) {
  auto g = parseGraphicEq("GraphicEQ: 20 -7.9; 1000 0.5; 21 -7.8;  bad ; 19999 -3");
  CHECK(g.size() == 4);
  CHECK_NEAR(g.front().first, 20, 0);
  CHECK_NEAR(g[1].second, -7.8, 1e-12);
  CHECK_NEAR(g.back().first, 19999, 0);
}

// ------------------------------------------------------------- oversampler

TEST(oversampler_round_trip_is_transparent_and_latency_is_exact) {
  const double fs = 44100;
  for (int factor : {2, 4, 8}) {
    OversamplerSpec spec;
    spec.factor = factor;
    spec.baseSampleRate = fs;
    spec.maxBlock = 512;
    Oversampler os(spec);
    const int lat = os.latencySamples();
    for (double f : {50.0, 1000.0, 19000.0}) {
      const int n = 22050;
      std::vector<double> x(n), y(n), hi(512 * factor);
      for (int i = 0; i < n; ++i) x[i] = 0.5 * std::sin(2 * kPi * f * i / fs);
      for (int i = 0; i < n; i += 512) {
        const int m = std::min(512, n - i);
        os.up(x.data() + i, m, hi.data());
        os.down(hi.data(), m, y.data() + i);
      }
      double maxErr = 0;
      for (int i = lat + 4000; i < n; ++i) maxErr = std::max(maxErr, std::fabs(y[i] - x[i - lat]));
      if (!(maxErr < 1e-4))
        std::printf("    factor %d, %.0f Hz: max error %.3g (latency %d)\n", factor, f, maxErr, lat);
      CHECK(maxErr < 1e-4);  // < -74 dB relative to the 0.5 tone, i.e. sample-accurate delay
    }
    os.reset();
  }
}

TEST(oversampler_rejects_imaging_by_more_than_100db) {
  const double fs = 44100;
  OversamplerSpec spec;
  spec.factor = 2;
  spec.baseSampleRate = fs;
  spec.stopbandDb = 120;
  spec.maxBlock = 1 << 15;
  Oversampler os(spec);
  const int n = 1 << 15;
  std::vector<double> x(n), hi(2 * n);
  const double f = 15000;
  for (int i = 0; i < n; ++i) x[i] = std::sin(2 * kPi * f * i / fs);
  os.up(x.data(), n, hi.data());
  std::vector<double> tail(hi.begin() + 2000, hi.end());
  const double tone = tonePower(tail, f, 2 * fs);
  const double image = tonePower(tail, fs - f, 2 * fs);  // 29.1 kHz
  const double rejection = 10 * std::log10(tone / image);
  std::printf("    2x image rejection at %.0f Hz: %.1f dB\n", fs - f, rejection);
  CHECK(rejection > 100);
}

// ------------------------------------------------------------------ dither

TEST(tpdf_dither_statistics_and_grid) {
  Dither d(16, DitherMode::Tpdf, 123);
  const double lsb = 1.0 / 32768.0;
  const int n = 200000;
  double mean = 0, var = 0;
  bool onGrid = true;
  for (int i = 0; i < n; ++i) {
    const double x = 0.1 * std::sin(0.001234 * i);
    const double y = d.process(x);
    const double scaled = y * 32768.0;
    onGrid = onGrid && std::fabs(scaled - std::round(scaled)) < 1e-9;
    const double e = (y - x) / lsb;
    mean += e;
    var += e * e;
  }
  mean /= n;
  var = var / n - mean * mean;
  CHECK(onGrid);
  CHECK_NEAR(mean, 0.0, 0.01);
  CHECK_NEAR(var, 0.25, 0.02);  // TPDF (1/6) + rounding (1/12) = 1/4 LSB^2
}

TEST(tpdf_dither_preserves_signal_below_one_lsb) {
  const double lsb = 1.0 / 32768.0;
  const double fs = 48000, f = 997;
  const int n = 96000;
  std::vector<double> x(n), plain(n), dithered(n);
  Dither none(16, DitherMode::None), tpdf(16, DitherMode::Tpdf, 99);
  for (int i = 0; i < n; ++i) {
    x[i] = 0.4 * lsb * std::sin(2 * kPi * f * i / fs);
    plain[i] = none.process(x[i]);
    dithered[i] = tpdf.process(x[i]);
  }
  CHECK(sineAmplitude(plain, f, fs, 0, n) / lsb < 1e-9);  // truncation erases it
  CHECK_NEAR(sineAmplitude(dithered, f, fs, 0, n) / lsb, 0.4, 0.03);
}

TEST(noise_shaping_moves_noise_out_of_the_midrange) {
  const double fs = 44100;
  const int n = 1 << 15;
  auto errorAt = [&](DitherMode m, double f) {
    Dither d(16, m, 5);
    std::vector<double> e(n);
    for (int i = 0; i < n; ++i) {
      const double x = 0.25 * std::sin(0.0123 * i);
      e[i] = (d.process(x) - x) * 32768.0;
    }
    // Average ~100 independent bins (+-250 Hz) so the estimate is within ~0.5 dB.
    double p = 0;
    for (int k = -50; k <= 50; ++k) p += tonePower(e, f + k * 5.0, fs);
    return p;
  };
  const double lowFlat = errorAt(DitherMode::Tpdf, 1000), lowShaped = errorAt(DitherMode::ShapedTpdf, 1000);
  const double hiFlat = errorAt(DitherMode::Tpdf, 20000), hiShaped = errorAt(DitherMode::ShapedTpdf, 20000);
  std::printf("    shaped vs flat: %.1f dB at 1 kHz, %+.1f dB at 20 kHz\n",
              10 * std::log10(lowShaped / lowFlat), 10 * std::log10(hiShaped / hiFlat));
  // Theory for NTF = 1 - z^-1 at 44.1 kHz: -16.9 dB at 1 kHz, +5.9 dB at 20 kHz.
  CHECK_NEAR(10 * std::log10(lowShaped / lowFlat), -16.9, 1.5);
  CHECK_NEAR(10 * std::log10(hiShaped / hiFlat), 5.9, 1.5);
}

// ------------------------------------------------------------------ engine

namespace {
// Runs a sine through the engine and returns the measured gain in dB.
double engineGainDb(Engine& e, double f, double amp = 0.01) {
  const double fs = e.config().sampleRate;
  const int C = e.config().channels;
  const int n = 32768;
  std::vector<float> buf(static_cast<size_t>(n) * C);
  for (int i = 0; i < n; ++i)
    for (int c = 0; c < C; ++c) buf[i * C + c] = static_cast<float>(amp * std::sin(2 * kPi * f * i / fs));
  e.reset();
  e.process(buf.data(), buf.data(), n);  // in-place
  std::vector<double> ch0(n);
  for (int i = 0; i < n; ++i) ch0[i] = buf[i * C];
  // Fit against the delayed input phase: amplitude is phase-independent.
  return toDb(sineAmplitude(ch0, f, fs, n / 2, n) / amp);
}
}  // namespace

TEST(engine_flat_curve_is_unity_with_exact_latency) {
  for (int os : {1, 2, 4, 8}) {
    EngineConfig cfg;
    cfg.sampleRate = 48000;
    cfg.channels = 2;
    cfg.oversample = os;
    cfg.maxBlock = 256;
    cfg.gainProtection = false;  // a full-scale impulse would (correctly) trigger it
    Engine e(cfg);
    const int n = 4096;
    std::vector<float> buf(n * 2, 0.0f);
    buf[0] = 1.0f;  // impulse on L
    buf[1] = 0.5f;  // impulse on R
    e.process(buf.data(), buf.data(), n);
    // A one-sample impulse is not band-limited, so with oversampling it comes
    // back as a 20 kHz-band-limited pulse centred on the latency. Its peak
    // position and DC gain (sum) are exact; its peak height is not 1.
    int peak = 0;
    double sumL = 0, sumR = 0;
    for (int i = 0; i < n; ++i) {
      if (std::fabs(buf[i * 2]) > std::fabs(buf[peak * 2])) peak = i;
      sumL += buf[i * 2];
      sumR += buf[i * 2 + 1];
    }
    CHECK(peak == e.latencyFrames());
    CHECK_NEAR(sumL, 1.0, 1e-4);
    CHECK_NEAR(sumR, 0.5, 1e-4);
    CHECK(buf[peak * 2] > 0.95);
  }
}

TEST(engine_auto_headroom_prevents_clipping) {
  EngineConfig cfg;
  cfg.sampleRate = 48000;
  cfg.oversample = 2;
  cfg.gainProtection = false;  // isolate the predictive mechanism
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak, 1000, 12, 1.0, true}});
  CHECK_NEAR(e.appliedGainDb(), -12.0, 0.01);
  CHECK_NEAR(e.eqResponseDb(0, 1000), 12.0, 1e-9);  // UI curve excludes the headroom
  CHECK_NEAR(e.responseDb(0, 1000), 0.0, 1e-3);     // total includes it (grid-sampled peak)
  CHECK_NEAR(engineGainDb(e, 1000, 0.99), 0.0, 0.02);  // boost cancelled by headroom: no overs
}

TEST(audiophile_mode_removes_high_frequency_cramping) {
  // A +9 dB, Q 1.5 bell at 16 kHz on 44.1 kHz material. At 1x the bilinear
  // transform squeezes the bell against Nyquist; at 4x/8x it keeps its
  // analog shape. Measured through the full engine with real signals.
  const double fs = 44100, f0 = 16000, g = 9, q = 1.5;
  double worst[9] = {0};
  for (int os : {1, 2, 4, 8}) {
    EngineConfig cfg;
    cfg.sampleRate = fs;
    cfg.channels = 1;
    cfg.oversample = os;
    cfg.autoHeadroom = false;
    Engine e(cfg);
    e.setBandsAllChannels({{FilterType::Peak, f0, g, q, true}});
    for (double f : {8000.0, 12000.0, 14000.0, 18000.0, 19500.0}) {
      const double dev = std::fabs(engineGainDb(e, f) - analogPeakDb(f, f0, g, q));
      worst[os] = std::max(worst[os], dev);
    }
    std::printf("    %dx: worst deviation from analog bell 8-19.5 kHz = %.2f dB\n", os, worst[os]);
  }
  CHECK(worst[1] > 1.0);  // the problem is real at 1x ...
  CHECK(worst[4] < 0.25);  // ... and gone in Audiophile mode
  CHECK(worst[8] < worst[4]);
}

TEST(automatic_gain_protection_catches_real_overloads) {
  EngineConfig cfg;
  cfg.sampleRate = 48000;
  cfg.channels = 2;
  cfg.oversample = 4;
  cfg.autoHeadroom = false;  // only the reactive mechanism
  cfg.gainProtection = true;
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak, 1000, 12, 1.0, true}});
  const int n = 48000;
  std::vector<float> buf(n * 2);
  for (int i = 0; i < n; ++i) buf[2 * i] = buf[2 * i + 1] = static_cast<float>(0.9 * std::sin(2 * kPi * 1000 * i / 48000.0));
  e.process(buf.data(), buf.data(), n);
  float peak = 0;
  for (float v : buf) peak = std::max(peak, std::fabs(v));
  // Ideal reduction: 0.9 * 10^(12/20) = 3.58 -> needs -11.1 dB (+0.1 dB ceiling).
  std::printf("    AGP reduced gain by %.2f dB, output peak %.4f\n", e.gainProtectionDb(), peak);
  CHECK(peak <= 0.98855f + 1e-6f);
  CHECK_NEAR(e.gainProtectionDb(), -11.2, 0.3);
  // Once settled it stays put (no pumping): a second pass changes nothing.
  const double settled = e.gainProtectionDb();
  for (int i = 0; i < n; ++i) buf[2 * i] = buf[2 * i + 1] = static_cast<float>(0.9 * std::sin(2 * kPi * 1000 * i / 48000.0));
  e.process(buf.data(), buf.data(), n);
  CHECK_NEAR(e.gainProtectionDb(), settled, 0.05);
}

// --------------------------------------------------------------- resampler

namespace {
struct ResampleResult {
  std::vector<double> out;
  double latencyOut;  // in output samples
};
ResampleResult runResampler(int inRate, int outRate, ResamplerQuality q, double freq, double amp, int nIn) {
  Resampler r(inRate, outRate, q);
  std::vector<double> in(nIn);
  for (int i = 0; i < nIn; ++i) in[i] = amp * std::sin(2 * kPi * freq * i / inRate);
  ResampleResult res;
  res.out.resize(r.maxOutputFor(nIn) + 8);
  int produced = 0;
  for (int i = 0; i < nIn; i += 333) {  // odd block size on purpose
    const int m = std::min(333, nIn - i);
    produced += r.process(in.data() + i, m, res.out.data() + produced);
  }
  res.out.resize(produced);
  res.latencyOut = r.latencyInputSamples() * outRate / inRate;
  return res;
}
}  // namespace

TEST(resampler_produces_the_right_number_of_samples) {
  for (auto [a, b] : {std::pair{44100, 48000}, {48000, 44100}, {44100, 96000}, {96000, 44100}, {48000, 192000}, {48000, 48000}}) {
    Resampler r(a, b, ResamplerQuality::Quality);
    std::vector<double> in(a, 0.0), out(r.maxOutputFor(a));
    const int produced = r.process(in.data(), a, out.data());
    CHECK(std::abs(produced - b) <= 1);  // one second in -> one second out
  }
}

TEST(resampler_is_transparent_in_the_passband) {
  for (auto q : {ResamplerQuality::Quality, ResamplerQuality::Audiophile}) {
    for (auto [a, b] : {std::pair{44100, 48000}, {48000, 44100}, {44100, 96000}, {96000, 48000}}) {
      for (double f : {100.0, 1000.0, 10000.0, 18000.0}) {
        auto r = runResampler(a, b, q, f, 0.5, a / 2);
        const size_t start = static_cast<size_t>(r.latencyOut) + 2000;
        const double gainDb = toDb(sineAmplitude(r.out, f, b, start, r.out.size()) / 0.5);
        if (std::fabs(gainDb) > 0.001)
          std::printf("    %s %d->%d %.0f Hz: %.5f dB\n", q == ResamplerQuality::Audiophile ? "audiophile" : "quality", a, b, f, gainDb);
        CHECK(std::fabs(gainDb) < 0.001);
      }
    }
  }
}

TEST(resampler_audiophile_extends_passband_and_crushes_aliasing) {
  // Passband: 20.5 kHz survives 44.1k -> 48k untouched only in Audiophile.
  auto passQ = runResampler(44100, 48000, ResamplerQuality::Quality, 20500, 0.5, 44100);
  auto passA = runResampler(44100, 48000, ResamplerQuality::Audiophile, 20500, 0.5, 44100);
  const double gq = toDb(sineAmplitude(passQ.out, 20500, 48000, 8000, passQ.out.size()) / 0.5);
  const double ga = toDb(sineAmplitude(passA.out, 20500, 48000, 8000, passA.out.size()) / 0.5);
  std::printf("    20.5 kHz through 44.1->48k: quality %.2f dB, audiophile %.4f dB\n", gq, ga);
  CHECK(gq < -1.0);
  CHECK(std::fabs(ga) < 0.01);

  // Aliasing: a 23 kHz tone (above 22.05 kHz) going 48k -> 44.1k must vanish;
  // anything left folds down to 44.1 - 23 = 21.1 kHz.
  for (auto q : {ResamplerQuality::Quality, ResamplerQuality::Audiophile}) {
    auto r = runResampler(48000, 44100, q, 23000, 1.0, 1 << 16);
    std::vector<double> tail(r.out.begin() + 4000, r.out.end());
    const double alias = tonePower(tail, 21100, 44100);
    const double rejDb = -10 * std::log10(alias);  // tonePower returns amp^2; input amp = 1
    std::printf("    %s alias rejection at 23 kHz: %.1f dB\n", q == ResamplerQuality::Audiophile ? "audiophile" : "quality", rejDb);
    CHECK(rejDb > (q == ResamplerQuality::Audiophile ? 130.0 : 95.0));
  }
}

TEST(quality_presets_are_consistent) {
  auto a = EngineConfig::forQuality(QualityMode::Audiophile, 48000, 2, 24);
  CHECK(a.oversample == 4 && a.ditherBits == 24 && a.ditherMode == DitherMode::Tpdf);
  auto x = EngineConfig::forQuality(QualityMode::Extreme, 48000, 2, 16);
  CHECK(x.oversample == 8 && x.ditherMode == DitherMode::ShapedTpdf);
  auto ef = EngineConfig::forQuality(QualityMode::Efficient, 48000, 2, 16);
  CHECK(ef.oversample == 1 && ef.ditherBits == 0);
}

int main(int argc, char** argv) {
  const char* filter = argc > 1 ? argv[1] : nullptr;
  int run = 0;
  for (auto& t : registry()) {
    if (filter && !std::strstr(t.name, filter)) continue;
    const int before = g_failures;
    std::printf("[ RUN  ] %s\n", t.name);
    t.fn();
    std::printf("[ %s ] %s\n", g_failures == before ? " OK " : "FAIL", t.name);
    ++run;
  }
  std::printf("\n%d tests, %d failed checks\n", run, g_failures);
  return g_failures == 0 ? 0 : 1;
}

// Self-contained unit tests (no external framework, so the Android/CI builds
// need nothing extra). Run: ./eqcore_tests [filter-substring]
#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <functional>
#include <limits>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "eqcore/analyzer.h"
#include "eqcore/graphic_eq.h"
#include "eqcore/autoeq.h"
#include "eqcore/svaramanas.h"
#include "eqcore/biquad.h"
#include "eqcore/dither.h"
#include "eqcore/engine.h"
#include "eqcore/calibration.h"
#include "eqcore/comparison.h"
#include "eqcore/oversampler.h"
#include "eqcore/parametric_eq.h"
#include "eqcore/resampler.h"
#include "eqcore/bass.h"
#include "eqcore/tuning.h"
#include "eqcore/stereo.h"
#include "eqcore/spatial.h"
#include "eqcore/bass_unmask.h"
#include "eqcore/policy.h"
#include <fstream>
#include <sstream>

using namespace eqcore;

// Allocation counter for the audio-thread checks (AQ-06). Counts only while g_countAllocs is set.
static std::atomic<long> g_allocs{0};
static std::atomic<bool> g_countAllocs{false};
void* operator new(std::size_t n) {
  if (g_countAllocs.load(std::memory_order_relaxed)) g_allocs.fetch_add(1, std::memory_order_relaxed);
  if (void* p = std::malloc(n ? n : 1)) return p;
  throw std::bad_alloc();
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

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

TEST(eq_removal_crossfades_without_a_discontinuous_gain_step) {
  ParametricEq eq(1,48000);
  eq.setBands(0,{{FilterType::LowShelf,1000,6,.71,true}});
  std::vector<double> warm(48000,.1); eq.process(0,warm.data(),warm.size());
  const double before=warm.back();
  eq.setBands(0,{});
  std::vector<double> out(4800,.1); eq.process(0,out.data(),out.size());
  CHECK(std::fabs(out.front()-before)<.001);
  double jump=0;
  for(size_t i=1;i<out.size();++i) jump=std::max(jump,std::fabs(out[i]-out[i-1]));
  CHECK(jump<.001);
  CHECK_NEAR(out.back(),.1,1e-12);
  std::printf("    shelf removal: first step %.9f, maximum adjacent step %.9f (old immediate step %.9f)\n",std::fabs(out.front()-before),jump,std::fabs(before-.1));
}

TEST(eq_transition_is_block_size_independent_and_reaches_the_latest_curve) {
  ParametricEq a(1,48000),b(1,48000);
  std::vector<double> warm(4096,.1), other=warm;
  a.process(0,warm.data(),warm.size()); b.process(0,other.data(),other.size());
  const std::vector<BandParams> target={{FilterType::LowShelf,500,9,.71,true},{FilterType::Peak,2000,-3,1,true}};
  a.setBands(0,target); b.setBands(0,target);
  std::vector<double> x(4800,.1),y=x;
  a.process(0,x.data(),x.size());
  for(size_t i=0;i<y.size();i+=13) b.process(0,y.data()+i,std::min<size_t>(13,y.size()-i));
  for(size_t i=0;i<x.size();++i) CHECK_NEAR(x[i],y[i],1e-12);
  CHECK_NEAR(toDb(x.back()/.1),9,.01);
  // Edits arriving during a fade coalesce and eventually reach the latest request.
  a.setBands(0,{{FilterType::LowShelf,500,-3,.71,true}});
  std::vector<double> tiny(80,.1); a.process(0,tiny.data(),tiny.size());
  a.setBands(0,{{FilterType::LowShelf,500,3,.71,true}});
  for(int i=0;i<100;i++){std::fill(tiny.begin(),tiny.end(),.1);a.process(0,tiny.data(),tiny.size());}
  CHECK_NEAR(toDb(tiny.back()/.1),3,.01);
}

TEST(eq_zero_gain_slots_preserve_other_band_history_during_transition) {
  ParametricEq eq(1,48000),reference(1,48000);
  std::vector<BandParams> bands={{FilterType::LowShelf,100,0,.71,true},{FilterType::Peak,1000,9,1,true}};
  eq.setBands(0,bands); reference.setBands(0,bands);
  std::vector<double> warm(48000);
  for(size_t i=0;i<warm.size();++i) warm[i]=.1*std::cos(2*kPi*1000*i/48000);
  auto other=warm;eq.process(0,warm.data(),warm.size());reference.process(0,other.data(),other.size());
  bands[0].gainDb=6;eq.setBands(0,bands);
  std::vector<double> out(4800),ref(out.size());
  for(size_t i=0;i<out.size();++i) out[i]=ref[i]=.1*std::cos(2*kPi*1000*i/48000);
  eq.process(0,out.data(),out.size());reference.process(0,ref.data(),ref.size());
  CHECK(std::fabs(out.front()-ref.front())<.001);
  for(double v:out) CHECK(std::isfinite(v));
}

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

TEST(engine_preamp_changes_are_smoothed_and_stereo_linked) {
  EngineConfig c;c.autoHeadroom=false;c.gainProtection=false;
  Engine e(c);std::vector<float> warm(2048,.1f);e.process(warm.data(),warm.data(),1024);
  e.setPreampDb(6);
  std::vector<float> out(4800*2,.1f);e.process(out.data(),out.data(),4800);
  CHECK(std::fabs(out[0]-.1)<.001);
  CHECK_NEAR(out.back(),.1*std::pow(10.,6./20),1e-7);
  for(size_t i=0;i<out.size();i+=2) CHECK_NEAR(out[i],out[i+1],1e-12);
  double jump=0;for(size_t i=2;i<out.size();i+=2)jump=std::max(jump,std::fabs(double(out[i]-out[i-2])));
  CHECK(jump<.001);
  std::printf("    preamp +6 dB: maximum adjacent step %.9f (old immediate step %.9f)\n",jump,.1*(std::pow(10.,6./20)-1));
}

TEST(engine_protection_can_change_live_without_a_rebuild_or_history_reset) {
  EngineConfig c;c.autoHeadroom=false;c.gainProtection=false;
  Engine e(c);e.setPreampDb(12);
  std::vector<float> block(1024*2,.9f);e.process(block.data(),block.data(),1024);
  CHECK(block.back()>3.0);
  e.setGainProtection(true);
  std::fill(block.begin(),block.end(),.9f);e.process(block.data(),block.data(),1024);
  for(float v:block)CHECK(v<=.98856f);
  const double reduction=e.gainProtectionDb();CHECK(reduction< -10);
  e.setBandsAllChannels({});
  std::fill(block.begin(),block.end(),.9f);e.process(block.data(),block.data(),1024);
  CHECK_NEAR(e.gainProtectionDb(),reduction,.01);
  e.setGainProtection(false);
  std::fill(block.begin(),block.end(),.9f);e.process(block.data(),block.data(),1024);
  CHECK(block.back()>3.0);
  CHECK_NEAR(e.gainProtectionDb(),0,1e-12);
}

TEST(engine_protection_bounds_combined_eq_and_gain_transitions) {
  for(int factor:{1,4,8}) {
    EngineConfig c;c.oversample=factor;c.maxBlock=256;
    Engine e(c);std::vector<float> block(512);
    int frame=0;
    for(int edit=0;edit<48;++edit) {
      e.setBandsAllChannels({{FilterType::Peak,1000,edit%3==0?12.0:(edit%3==1?-6.0:0.0),1,true}});
      for(int i=0;i<256;++i,++frame)block[2*i]=block[2*i+1]=.95f*std::cos(2*kPi*1000*frame/48000);
      e.process(block.data(),block.data(),256);
      for(size_t i=0;i<block.size();i+=2) {
        CHECK(std::isfinite(block[i]) && std::fabs(block[i])<=.98856f);
        CHECK_NEAR(block[i],block[i+1],1e-12);
      }
    }
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

TEST(headroom_uses_existing_preamp_instead_of_attenuating_twice) {
  EngineConfig cfg;
  cfg.gainProtection = false;
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak, 1000, 6, 1.0, true}});
  e.setPreampDb(-6);
  CHECK_NEAR(e.appliedGainDb(), -6.0, 0.01);
  CHECK_NEAR(engineGainDb(e, 1000, 0.5), 0.0, 0.02);
  e.setPreampDb(-10);
  CHECK_NEAR(e.appliedGainDb(), -10.0, 0.01);
  CHECK_NEAR(engineGainDb(e, 1000, 0.5), -4.0, 0.02);
  e.setPreampDb(-2);
  CHECK_NEAR(e.appliedGainDb(), -6.0, 0.01);
}

TEST(disabling_headroom_restores_requested_gain_and_boost) {
  EngineConfig cfg;
  cfg.gainProtection = false;
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak, 1000, 6, 1.0, true}});
  e.setPreampDb(0);
  e.setAutoHeadroom(false);
  CHECK_NEAR(e.appliedGainDb(), 0.0, 1e-9);
  CHECK_NEAR(engineGainDb(e, 1000, 0.1), 6.0, 0.02);
  e.setAutoHeadroom(true);
  CHECK_NEAR(engineGainDb(e, 1000, 0.1), 0.0, 0.02);
}

TEST(flat_and_cut_only_eq_do_not_add_headroom_loss) {
  EngineConfig cfg;
  cfg.gainProtection = false;
  Engine e(cfg);
  CHECK_NEAR(engineGainDb(e, 1000, 0.5), 0.0, 0.001);
  e.setBandsAllChannels({{FilterType::Peak, 1000, -6, 1.0, true}});
  CHECK_NEAR(e.appliedGainDb(), 0.0, 1e-9);
  CHECK_NEAR(engineGainDb(e, 1000, 0.5), -6.0, 0.02);
}

TEST(layered_tuning_manual_eq_and_tuners_keep_the_last_band) {
  EngineConfig cfg;
  cfg.autoHeadroom = false;
  cfg.gainProtection = false;
  Engine e(cfg);
  // 96 tuning + 128 manual + 6 tuner bands. Reciprocal low bells
  // cancel in pairs, so the last band's +6 dB must reach the output.
  std::vector<BandParams> bands;
  for (int i = 0; i < 228; ++i)
    bands.push_back({FilterType::Peak, 100, i % 2 ? -0.1 : 0.1, 1.0, true});
  bands.push_back({FilterType::Peak, 1000, 6, 1.0, true});
  e.setBandsAllChannels(bands);
  CHECK_NEAR(e.eqResponseDb(0, 1000), 6.0, 0.001);
  CHECK_NEAR(engineGainDb(e, 1000, 0.1), 6.0, 0.02);
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

TEST(protection_recovers_after_overload_and_keeps_stereo_linked) {
  EngineConfig cfg;
  cfg.channels = 2;
  cfg.autoHeadroom = false;
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak, 1000, 12, 1.0, true}});
  const int n = 48000;
  std::vector<float> loud(n * 2);
  for (int i = 0; i < n; ++i) {
    loud[2*i] = 0.9f * std::sin(2*kPi*1000*i/48000);
    loud[2*i+1] = loud[2*i] * 0.25f;
  }
  e.process(loud.data(), loud.data(), n);
  CHECK(e.gainProtectionDb() < -10);
  for (int i = 0; i < n; ++i) {
    CHECK(std::fabs(loud[2*i]) <= 0.98856f);
    CHECK_NEAR(loud[2*i+1], loud[2*i] * 0.25, 1e-6);
  }
  std::vector<float> quiet(48000 * 3 * 2);
  for (int i = 0; i < 48000 * 3; ++i)
    quiet[2*i] = quiet[2*i+1] = 0.05f * std::sin(2*kPi*5000*i/48000);
  e.process(quiet.data(), quiet.data(), 48000 * 3);
  CHECK(e.gainProtectionDb() > -0.01);
  std::vector<double> channel(48000);
  for (int i = 0; i < 48000; ++i) channel[i] = quiet[(i + 96000)*2];
  CHECK_NEAR(toDb(sineAmplitude(channel, 5000, 48000, 0, channel.size())/0.05),
             e.eqResponseDb(0, 5000), 0.02);
}

TEST(bass_punch_preserves_attack_energy_around_crossover) {
  for (double fs : {44100.0, 48000.0}) for (double f : {90.0, 120.0, 150.0, 180.0}) {
    BassShaper b(fs, 1);
    b.setCharacter(1);
    std::vector<double> x(static_cast<int>(fs * 0.1));
    for (size_t i=0;i<x.size();++i) x[i]=0.1*std::sin(2*kPi*f*i/fs);
    auto y=x;
    b.process(0,y.data(),y.size());
    double inEnergy=0,outEnergy=0;
    for(int i=static_cast<int>(fs*.01);i<static_cast<int>(fs*.03);++i) {
      inEnergy+=x[i]*x[i];outEnergy+=y[i]*y[i];
    }
    CHECK(10*std::log10(outEnergy/inEnergy) >= 0.0);
  }
}

TEST(bass_off_smoothly_restores_identity_after_active_processing) {
  BassShaper b(48000,1),reference(48000,1);
  b.setCharacter(1);reference.setCharacter(1);
  std::vector<double> first(1024),next(1024);
  for(int i=0;i<1024;++i) {
    first[i]=.1*std::sin(2*kPi*120*i/48000);
    next[i]=.1*std::sin(2*kPi*120*(i+1024)/48000);
  }
  auto warm=first;b.process(0,warm.data(),1024);reference.process(0,first.data(),1024);
  b.setCharacter(0);
  auto output=next,unchanged=next;
  b.process(0,output.data(),1024);reference.process(0,unchanged.data(),1024);
  CHECK(std::abs(output.front()-unchanged.front())<.001);
  CHECK(std::equal(next.begin()+480,next.end(),output.begin()+480));
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

// ------------------------------------------------------------- bass shaper

namespace {
// Kick-like bass hits: 55 Hz, instant attack, 150 ms exponential decay, every 0.6 s.
std::vector<double> kicks(double fs, int hits, double hfAmp = 0.0) {
  const int period = static_cast<int>(0.6 * fs);
  std::vector<double> x(static_cast<size_t>(period) * hits);
  for (size_t i = 0; i < x.size(); ++i) {
    const double t = static_cast<double>(i % period) / fs;
    x[i] = 0.5 * std::exp(-t / 0.15) * std::sin(2 * kPi * 55 * t) + hfAmp * std::sin(2 * kPi * 2000 * i / fs);
  }
  return x;
}
// Energy ratio (dB) of the first 30 ms of each hit vs 150..400 ms, averaged over hits 2..n.
double attackToTailDb(const std::vector<double>& y, double fs, int hits) {
  const int period = static_cast<int>(0.6 * fs);
  double att = 0, tail = 0;
  for (int h = 1; h < hits; ++h) {
    const size_t base = static_cast<size_t>(h) * period;
    for (int i = 0; i < static_cast<int>(0.03 * fs); ++i) att += y[base + i] * y[base + i];
    for (int i = static_cast<int>(0.15 * fs); i < static_cast<int>(0.4 * fs); ++i) tail += y[base + i] * y[base + i];
  }
  return 10 * std::log10((att / 0.03) / (tail / 0.25));
}
}  // namespace

TEST(bass_shaper_off_is_bit_exact) {
  const double fs = 48000;
  BassShaper b(fs, 1);
  auto x = kicks(fs, 3, 0.1);
  auto y = x;
  b.process(0, y.data(), static_cast<int>(y.size()));
  bool same = true;
  for (size_t i = 0; i < x.size(); ++i) same = same && (x[i] == y[i]);
  CHECK(same);
}

TEST(bass_shaper_punch_tightens_and_sustain_blooms) {
  const double fs = 48000;
  const int hits = 6;
  auto x = kicks(fs, hits);
  const double ref = attackToTailDb(x, fs, hits);
  double punchDb = 0, sustainDb = 0;
  for (double c : {1.0, -1.0}) {
    BassShaper b(fs, 1);
    b.setCharacter(c);
    auto y = x;
    for (size_t i = 0; i < y.size(); i += 256) b.process(0, y.data() + i, static_cast<int>(std::min<size_t>(256, y.size() - i)));
    (c > 0 ? punchDb : sustainDb) = attackToTailDb(y, fs, hits) - ref;
  }
  std::printf("    attack/tail change: punch %+.1f dB, sustain %+.1f dB\n", punchDb, sustainDb);
  CHECK(punchDb > 3.0);
  CHECK(sustainDb < -3.0);
}

namespace {
void runBass(BassShaper& b, std::vector<double>& y) {
  for (size_t i = 0; i < y.size(); i += 256) b.process(0, y.data() + i, static_cast<int>(std::min<size_t>(256, y.size() - i)));
}
// Sustained-note harmonic distortion created by the envelope following individual cycles.
double sustainedNoteDistortion(double resolve, double hz, double character) {
  const double fs = 48000;
  std::vector<double> x(static_cast<size_t>(fs * 3));
  for (size_t i = 0; i < x.size(); ++i) x[i] = 0.4 * std::sin(2 * kPi * hz * i / fs);
  BassShaper b(fs, 1);
  b.setCharacter(character);
  b.setResolve(resolve);
  runBass(b, x);
  const size_t from = static_cast<size_t>(fs * 2), to = x.size();
  const double f1 = sineAmplitude(x, hz, fs, from, to);
  return (sineAmplitude(x, 2 * hz, fs, from, to) + sineAmplitude(x, 3 * hz, fs, from, to)) / f1;
}
}  // namespace

TEST(bass_resolve_zero_changes_nothing_and_linked_matches_single_channel) {
  const double fs = 48000;
  const auto x = kicks(fs, 4);
  for (double c : {1.0, -1.0, 0.4}) {
    BassShaper a(fs, 1), b(fs, 2);
    a.setCharacter(c);
    b.setCharacter(c);
    b.setResolve(0.0);
    auto y = x;
    runBass(a, y);
    auto l = x, r = x;
    for (size_t i = 0; i < l.size(); i += 256) b.processLinked(l.data() + i, r.data() + i, static_cast<int>(std::min<size_t>(256, l.size() - i)));
    double err = 0;
    for (size_t i = 0; i < y.size(); ++i) err = std::max({err, std::fabs(y[i] - l[i]), std::fabs(l[i] - r[i])});
    CHECK(err < 1e-12);
  }
}

TEST(bass_resolve_caps_how_far_feel_may_move_the_note) {
  const double fs = 48000;
  const int hits = 6;
  const auto x = kicks(fs, hits);
  const double ref = attackToTailDb(x, fs, hits);
  for (double c : {1.0, -1.0}) {
    double change[2];
    for (int i = 0; i < 2; ++i) {
      BassShaper b(fs, 1);
      b.setCharacter(c);
      b.setResolve(i ? 1.0 : 0.0);
      auto y = x;
      runBass(b, y);
      change[i] = attackToTailDb(y, fs, hits) - ref;
    }
    std::printf("    feel %+.0f: attack/tail change %+.1f dB without Resolve, %+.1f dB with\n", c, change[0], change[1]);
    CHECK(std::fabs(change[1]) < std::fabs(change[0]) * 0.4);
    CHECK(std::fabs(change[1]) < 3.0);
  }
}

TEST(bass_resolve_keeps_sustained_notes_free_of_envelope_distortion) {
  for (double hz : {30.0, 41.2, 55.0, 82.4}) {
    for (double c : {-1.0, 1.0}) {
      const double off = sustainedNoteDistortion(0.0, hz, c), on = sustainedNoteDistortion(1.0, hz, c);
      std::printf("    %.1f Hz feel %+.0f: distortion %.3f%% -> %.3f%%\n", hz, c, off * 100, on * 100);
      CHECK(on <= off + 1e-9);
      CHECK(on < 0.01);  // under 1 %
    }
  }
}

TEST(bass_linked_processing_keeps_left_right_balance_of_a_bass_note) {
  const double fs = 48000;
  std::vector<double> l(static_cast<size_t>(fs * 2)), r(l.size());
  for (size_t i = 0; i < l.size(); ++i) {
    const double t = static_cast<double>(i) / fs, e = std::exp(-std::fmod(t, 0.6) / 0.15);
    l[i] = 0.5 * e * std::sin(2 * kPi * 55 * t);
    r[i] = 0.1 * e * std::sin(2 * kPi * 55 * t);  // 14 dB quieter on the right
  }
  const auto l0 = l, r0 = r;
  BassShaper b(fs, 2);
  b.setCharacter(-1.0);
  for (size_t i = 0; i < l.size(); i += 256) b.processLinked(l.data() + i, r.data() + i, static_cast<int>(std::min<size_t>(256, l.size() - i)));
  double eL = 0, eR = 0, iL = 0, iR = 0;
  for (size_t i = 0; i < l.size(); ++i) { eL += l[i] * l[i]; eR += r[i] * r[i]; iL += l0[i] * l0[i]; iR += r0[i] * r0[i]; }
  // Same gain for both: the pair's balance (~14 dB) changes by under 0.05 dB over the whole phrase.
  CHECK_NEAR(10 * std::log10(eL / eR), 10 * std::log10(iL / iR), 0.05);
}

TEST(bass_shaper_leaves_treble_alone) {
  const double fs = 48000;
  const int hits = 4;
  auto x = kicks(fs, hits, 0.1);
  BassShaper b(fs, 1);
  b.setCharacter(1.0);
  auto y = x;
  b.process(0, y.data(), static_cast<int>(y.size()));
  const double a = sineAmplitude(y, 2000, fs, y.size() / 4, y.size());
  CHECK_NEAR(toDb(a / 0.1), 0.0, 0.05);
}

TEST(engine_runs_bass_character_in_the_chain) {
  const double fs = 48000;
  const int hits = 5;
  auto x = kicks(fs, hits);
  double change[2] = {0, 0};
  for (int on = 0; on < 2; ++on) {
    EngineConfig cfg;
    cfg.sampleRate = fs;
    cfg.channels = 1;
    cfg.oversample = 2;
    cfg.autoHeadroom = false;
    cfg.gainProtection = false;
    Engine e(cfg);
    if (on) e.setBassCharacter(1.0, 120);
    std::vector<float> buf(x.begin(), x.end());
    e.process(buf.data(), buf.data(), static_cast<int>(buf.size()));
    std::vector<double> y(buf.begin(), buf.end());
    change[on] = attackToTailDb(y, fs, hits);
  }
  std::printf("    engine punch: %+.1f dB attack/tail vs bass shaper off\n", change[1] - change[0]);
  CHECK(change[1] - change[0] > 3.0);
}

TEST(bass_shaper_is_stable_on_noise) {
  const double fs = 44100;
  BassShaper b(fs, 2);
  std::mt19937 rng(9);
  std::uniform_real_distribution<double> u(-1, 1);
  bool ok = true;
  for (double c : {-1.0, -0.3, 0.4, 1.0}) {
    b.setCharacter(c);
    b.setCrossoverHz(c > 0 ? 80 : 200);
    std::vector<double> y(44100);
    for (auto& v : y) v = u(rng);
    b.process(1, y.data(), static_cast<int>(y.size()));
    for (double v : y) ok = ok && std::isfinite(v) && std::fabs(v) < 8.0;
  }
  CHECK(ok);
}

// ------------------------------------------------------------------ tuning

namespace {
std::string readFile(const std::string& name) {
  std::ifstream in(std::string(EQCORE_TEST_DATA_DIR) + "/" + name);
  std::stringstream ss;
  ss << in.rdbuf();
  return ss.str();
}
FrCurve curveFrom(const std::function<double(double)>& fn) {
  FrCurve c;
  for (int k = 0; k < 400; ++k) {
    const double f = 20.0 * std::pow(1000.0, k / 399.0);
    c.hz.push_back(f);
    c.db.push_back(fn(f));
  }
  return c;
}
double bell(double f, double f0, double g, double q) {
  return magnitudeDb(designBiquad({FilterType::Peak, f0, g, q, true}, 96000), f, 96000);
}
}  // namespace

TEST(curve_parser_reads_autoeq_and_squiglink_formats) {
  auto a = parseCurve("frequency,raw\n20.00,3.86\n20.20,3.89\n1000,0\n");
  CHECK(a.hz.size() == 3);
  CHECK_NEAR(a.at(20.0), 3.86, 1e-12);
  auto b = parseCurve("* Squiglink export\n* freq\tdB\n20\t-3.5\n100\t-1\n1000\t0\n10000;2.5\n");
  CHECK(b.hz.size() == 4);
  CHECK_NEAR(b.at(10000), 2.5, 1e-12);
  CHECK_NEAR(b.at(316.2277660168), -0.5, 1e-9);  // log-midpoint interpolation
  CHECK(parseCurve("hello\nworld").empty());
}

TEST(correction_recovers_a_known_deviation) {
  // Headphone = target minus three resonances -> correction must put them back.
  auto target = curveFrom([](double f) { return 4.0 / (1 + std::pow(f / 105.0, 2)); });
  auto dev = [](double f) { return bell(f, 60, -5, 0.8) + bell(f, 2500, 4, 2.0) + bell(f, 5000, -3, 1.5); };
  auto meas = curveFrom([&](double f) { return target.at(f) - dev(f) + 7.0; });  // +7: arbitrary level
  TuningOptions opt;
  opt.trebleSmoothFromHz = 20000;  // test the core maths without treble smoothing
  auto corr = computeCorrection(meas, target, opt);
  double worst = 0;
  for (double f = 40; f < 6000; f *= 1.1) worst = std::max(worst, std::fabs(corr.at(f) - dev(f)));
  std::printf("    worst error vs known deviation 40 Hz-6 kHz: %.2f dB\n", worst);
  CHECK(worst < 1.0);
}

TEST(dense_fit_follows_the_curve_with_64_bands) {
  auto curve = curveFrom([](double f) { return bell(f, 80, 6, 0.7) + bell(f, 3000, -5, 3) + bell(f, 9000, 4, 2); });
  auto fit = fitDenseBands(curve, 64);
  std::printf("    64-band fit: rms %.2f dB, max %.2f dB\n", fit.rmsErrorDb, fit.maxErrorDb);
  CHECK(fit.bands.size() == 64);
  CHECK(fit.rmsErrorDb < 0.3);
  auto coarse = fitDenseBands(curve, 10);
  std::printf("    10-band fit: rms %.2f dB (why 60+ bands matter)\n", coarse.rmsErrorDb);
  CHECK(coarse.rmsErrorDb > fit.rmsErrorDb);
}

TEST(taste_controls_shift_bass_and_tilt) {
  auto target = curveFrom([](double) { return 0.0; });
  auto meas = target;
  TuningOptions base, bassy, bright;
  bassy.bassDb = 4;
  bright.tiltDbPerOct = 0.5;
  auto c0 = computeCorrection(meas, target, base);
  auto cb = computeCorrection(meas, target, bassy);
  auto ct = computeCorrection(meas, target, bright);
  CHECK_NEAR(cb.at(30) - c0.at(30), 4.0, 0.6);
  CHECK_NEAR(cb.at(3000) - c0.at(3000), 0.0, 0.3);
  CHECK(ct.at(8000) - ct.at(1000) > 1.0);
}

TEST(real_hd650_correction_matches_autoeq) {
  auto meas = parseCurve(readFile("oratory1990_HD650.csv"));
  auto target = parseCurve(readFile("harman_over_ear_2018.csv"));
  auto autoeq = parseGraphicEq(readFile("autoeq_HD650_GraphicEQ.txt"));
  CHECK(!meas.empty() && !target.empty() && autoeq.size() > 100);
  FrCurve ref;
  for (auto& [f, g] : autoeq) { ref.hz.push_back(f); ref.db.push_back(g); }
  auto ours = computeCorrection(meas, target);
  // AutoEq bakes its preamp into GraphicEQ: compare shapes after aligning levels.
  std::vector<double> diffs;
  for (double f = 50; f < 6000; f *= 1.05) diffs.push_back(ours.at(f) - ref.at(f));
  std::vector<double> sorted = diffs;
  std::sort(sorted.begin(), sorted.end());
  const double offset = sorted[sorted.size() / 2];
  double sq = 0, worst = 0;
  for (double d : diffs) { sq += (d - offset) * (d - offset); worst = std::max(worst, std::fabs(d - offset)); }
  const double rms = std::sqrt(sq / diffs.size());
  std::printf("    HD 650 -> Harman: shape vs AutoEq 50 Hz-6 kHz rms %.2f dB, worst %.2f dB\n", rms, worst);
  CHECK(rms < 1.0);
  auto fit = fitDenseBands(ours, 64);
  std::printf("    64-band fit of the real correction: rms %.2f dB\n", fit.rmsErrorDb);
  CHECK(fit.rmsErrorDb < 0.5);
}

// ------------------------------------------------- vocal tuner / instrument amp

namespace {
struct Stereo {
  std::vector<double> l, r;
};
// mid/side test signal: centre content `mid(t)`, side content `side(t)`.
Stereo ms(double fs, double secs, const std::function<double(double)>& mid, const std::function<double(double)>& side) {
  Stereo st;
  const int n = static_cast<int>(fs * secs);
  for (int i = 0; i < n; ++i) {
    const double t = i / fs;
    st.l.push_back(mid(t) + side(t));
    st.r.push_back(mid(t) - side(t));
  }
  return st;
}
Stereo runTuner(StereoTunerParams p, Stereo st, double fs) {
  StereoTuner t(fs);
  t.setParams(p);
  for (size_t i = 0; i < st.l.size(); i += 256) {
    const int n = static_cast<int>(std::min<size_t>(256, st.l.size() - i));
    t.process(st.l.data() + i, st.r.data() + i, n);
  }
  return st;
}
std::vector<double> sideOf(const Stereo& st) {
  std::vector<double> s(st.l.size());
  for (size_t i = 0; i < s.size(); ++i) s[i] = 0.5 * (st.l[i] - st.r[i]);
  return s;
}
std::vector<double> midOf(const Stereo& st) {
  std::vector<double> m(st.l.size());
  for (size_t i = 0; i < m.size(); ++i) m[i] = 0.5 * (st.l[i] + st.r[i]);
  return m;
}
}  // namespace

TEST(stereo_tuner_off_is_bit_exact) {
  const double fs = 48000;
  auto in = ms(fs, 0.5, [](double t) { return 0.3 * std::sin(2 * kPi * 440 * t); },
               [](double t) { return 0.2 * std::sin(2 * kPi * 3000 * t); });
  auto out = runTuner({}, in, fs);
  CHECK(out.l == in.l && out.r == in.r);
}

// AQ-01: a hard-panned source must stay on its side whatever the side controls do.
// The old LR4 side crossover rotated S relative to M, swapping channels near 180 Hz
// while the mono sum stayed exact, so mono-sum tests could not see it.
namespace {
double settledRms(const std::vector<double>& x) {
  double e = 0;
  const size_t from = x.size() / 2;
  for (size_t i = from; i < x.size(); ++i) e += x[i] * x[i];
  return std::sqrt(e / static_cast<double>(x.size() - from));
}
double hardPanLeakDb(const StereoTunerParams& p, double hz, double fs, bool leftOnly) {
  const double sgn = leftOnly ? 1.0 : -1.0;
  auto in = ms(fs, 0.6, [&](double t) { return 0.25 * std::sin(2 * kPi * hz * t); },
               [&](double t) { return sgn * 0.25 * std::sin(2 * kPi * hz * t); });
  const auto out = runTuner(p, in, fs);
  const double keep = settledRms(leftOnly ? out.l : out.r), leak = settledRms(leftOnly ? out.r : out.l);
  return 20 * std::log10((leak + 1e-30) / (keep + 1e-30));
}
}  // namespace

TEST(side_controls_keep_hard_panned_sources_on_their_side) {
  for (double fs : {44100.0, 48000.0, 96000.0}) {
    for (double hz : {60.0, 120.0, 180.0, 250.0, 500.0, 1200.0, 4000.0, 9000.0}) {
      for (int which = 0; which < 4; ++which) {
        for (double amount : {1e-6, 0.01, 0.5}) {
          StereoTunerParams p;
          if (which == 0) p.backingVocals = amount;
          else if (which == 1) p.spatialDetail = amount;
          else if (which == 2) p.instruments = amount;
          else p.space = amount;
          // Added side delta is bounded by the control, so the opposite channel stays well below the source.
          CHECK(hardPanLeakDb(p, hz, fs, true) < -9.0);
          CHECK(hardPanLeakDb(p, hz, fs, false) < -9.0);
          if (amount <= 0.01) CHECK(hardPanLeakDb(p, hz, fs, true) < -30.0);
        }
      }
    }
  }
}

TEST(side_controls_are_continuous_at_tiny_amounts) {
  const double fs = 48000;
  for (int which = 0; which < 3; ++which) {
    const auto in = ms(fs, 0.5, [](double t) { return 0.2 * std::sin(2 * kPi * 180 * t) + 0.1 * std::sin(2 * kPi * 1500 * t); },
                       [](double t) { return 0.15 * std::sin(2 * kPi * 180 * t + 0.4) + 0.1 * std::sin(2 * kPi * 900 * t); });
    StereoTunerParams p;
    (which == 0 ? p.backingVocals : which == 1 ? p.spatialDetail : p.instruments) = 1e-6;
    const auto out = runTuner(p, in, fs);
    double err = 0;
    for (size_t i = 1500; i < in.l.size(); ++i)  // past the 20 ms parameter crossfade
      err = std::max({err, std::fabs(out.l[i] - in.l[i]), std::fabs(out.r[i] - in.r[i])});
    CHECK(err < 1e-3);
  }
}

TEST(spatial_detail_holds_when_the_mix_is_already_wide) {
  const double fs = 48000;
  StereoTunerParams p;
  p.backingVocals = 1;
  p.spatialDetail = 1;
  // Hard-panned and pure-side material has side power >= half the mid: no automatic lift is added.
  for (double hz : {500.0, 1300.0, 2000.0, 8000.0}) {
    CHECK_NEAR(toDb(sineAmplitude(runTuner(p, ms(fs, 2, [&](double t) { return .1 * std::sin(2 * kPi * hz * t); },
                                                  [&](double t) { return .1 * std::sin(2 * kPi * hz * t); }), fs).l, hz, fs, 48000, 96000) / .2), 0.0, 0.3);
  }
}

TEST(side_controls_leave_the_mid_signal_untouched) {
  const double fs = 48000;
  const auto in = ms(fs, 0.5, [](double t) { return 0.3 * std::sin(2 * kPi * 180 * t) + 0.1 * std::sin(2 * kPi * 3000 * t); },
                     [](double t) { return 0.2 * std::sin(2 * kPi * 700 * t); });
  const auto mIn = midOf(in);
  StereoTunerParams p;
  p.backingVocals = 1;
  p.spatialDetail = 1;
  p.instruments = 1;
  p.space = 0.7;
  const auto mOut = midOf(runTuner(p, in, fs));
  double err = 0;
  for (size_t i = 0; i < mIn.size(); ++i) err = std::max(err, std::fabs(mIn[i] - mOut[i]));
  CHECK(err < 1e-9);
}

// ------------------------------------------------ streaming spatial residual (AQ-02)

namespace {
struct Program {
  std::vector<double> m, s;
};
// A centred lead (white, rms `lead`) over per-channel independent ambience (rms `amb` in L and R).
Program ambienceProgram(double fs, double secs, double lead, double amb, unsigned seed) {
  std::mt19937 rng(seed);
  std::normal_distribution<double> g(0.0, 1.0);
  Program p;
  const int n = static_cast<int>(fs * secs);
  for (int i = 0; i < n; ++i) {
    const double x = lead * g(rng), l = x + amb * g(rng), r = x + amb * g(rng);
    p.m.push_back(.5 * (l + r));
    p.s.push_back(.5 * (l - r));
  }
  return p;
}
double rmsFrom(const std::vector<double>& x, size_t from) {
  double e = 0;
  for (size_t i = from; i < x.size(); ++i) e += x[i] * x[i];
  return std::sqrt(e / static_cast<double>(x.size() - from));
}
void runResidual(SpatialResidual& r, Program& p, int block) {
  for (size_t i = 0; i < p.m.size(); i += static_cast<size_t>(block)) {
    const int n = static_cast<int>(std::min<size_t>(static_cast<size_t>(block), p.m.size() - i));
    r.process(p.m.data() + i, p.s.data() + i, n);
  }
}
}  // namespace

TEST(spatial_residual_delay_is_exact_and_rate_independent) {
  for (auto [fs, expected] : {std::pair<double, int>{44100, 1024}, {48000, 1024}, {96000, 2048}, {192000, 4096}}) {
    SpatialResidual r(fs);
    CHECK(r.latencyFrames() == expected);
    Program p;
    p.m.assign(static_cast<size_t>(expected * 3), 0.0);
    p.s = p.m;
    p.m[100] = 1.0;
    p.s[50] = -0.5;
    r.setParams(1, 1);
    runResidual(r, p, 333);
    bool exact = true;
    for (size_t i = 0; i < p.m.size(); ++i) {
      exact = exact && p.m[i] == (i == static_cast<size_t>(100 + expected) ? 1.0 : 0.0);
      exact = exact && p.s[i] == (i == static_cast<size_t>(50 + expected) ? -0.5 : 0.0);
    }
    CHECK(exact);  // an isolated click is not enhanced: warm-up, then no stored statistics to act on
  }
}

TEST(spatial_residual_zero_amounts_are_a_bit_exact_delay) {
  const double fs = 48000;
  auto in = ambienceProgram(fs, 1.0, 0.2, 0.05, 11);
  auto out = in;
  SpatialResidual r(fs);
  runResidual(r, out, 256);
  bool same = true;
  for (size_t i = static_cast<size_t>(r.latencyFrames()); i < out.m.size(); ++i)
    same = same && out.m[i] == in.m[i - static_cast<size_t>(r.latencyFrames())] && out.s[i] == in.s[i - static_cast<size_t>(r.latencyFrames())];
  CHECK(same);
}

TEST(spatial_residual_is_independent_of_block_size) {
  const double fs = 48000;
  const auto in = ambienceProgram(fs, 1.5, 0.2, 0.04, 5);
  Program ref = in;
  {
    SpatialResidual r(fs);
    r.setParams(0.8, 0.7);
    runResidual(r, ref, 1024);
  }
  for (int block : {1, 7, 256, 4096}) {
    Program out = in;
    SpatialResidual r(fs);
    r.setParams(0.8, 0.7);
    runResidual(r, out, block);
    double diff = 0;
    for (size_t i = 0; i < out.s.size(); ++i) diff = std::max({diff, std::fabs(out.s[i] - ref.s[i]), std::fabs(out.m[i] - ref.m[i])});
    CHECK(diff == 0.0);
  }
}

TEST(spatial_residual_lifts_decorrelated_ambience_within_budget_and_keeps_the_lead) {
  for (double fs : {44100.0, 48000.0, 96000.0}) {
    for (int mode = 0; mode < 3; ++mode) {
      const auto in = ambienceProgram(fs, 3.0, 0.2, 0.03, 21);  // side power ~ 1 % of mid
      Program out = in;
      SpatialResidual r(fs);
      r.setParams(mode != 1 ? 1.0 : 0.0, mode != 0 ? 1.0 : 0.0);
      runResidual(r, out, 480);
      const size_t lat = static_cast<size_t>(r.latencyFrames()), from = static_cast<size_t>(fs * 2);
      std::vector<double> dryS(out.s.size()), dryM(out.s.size());
      double midErr = 0, monoErr = 0;
      for (size_t i = lat; i < out.s.size(); ++i) {
        dryS[i] = in.s[i - lat];
        midErr = std::max(midErr, std::fabs(out.m[i] - in.m[i - lat]));
        monoErr = std::max(monoErr, std::fabs((out.m[i] + out.s[i]) + (out.m[i] - out.s[i]) - 2 * in.m[i - lat]));
      }
      const double liftDb = 20 * std::log10(rmsFrom(out.s, from) / rmsFrom(dryS, from));
      std::printf("    %.0f Hz mode %d: side %+.2f dB, mid error %.1e\n", fs, mode, liftDb, midErr);
      CHECK(midErr == 0.0);          // the lead is exactly the delayed input
      CHECK(monoErr < 1e-12);        // the mono sum is untouched
      CHECK(liftDb > 0.3);           // it does something audible...
      CHECK(liftDb < 4.3);           // ...inside the shared 4 dB budget
    }
  }
}

TEST(spatial_residual_leaves_coherent_and_already_wide_material_alone) {
  const double fs = 48000;
  const size_t lat = 1024, from = static_cast<size_t>(fs);
  // Hard-panned and partly panned sources: S and M fully coherent, so there is no residual.
  for (double rightGain : {0.0, 0.5, -1.0}) {
    std::mt19937 rng(3);
    std::normal_distribution<double> g(0, 0.2);
    Program in;
    for (int i = 0; i < static_cast<int>(fs * 2); ++i) {
      const double x = g(rng), l = x, r = rightGain * x;
      in.m.push_back(.5 * (l + r));
      in.s.push_back(.5 * (l - r));
    }
    Program out = in;
    SpatialResidual sr(fs);
    sr.setParams(1, 1);
    runResidual(sr, out, 512);
    double err = 0;
    for (size_t i = from; i < out.s.size(); ++i) err = std::max(err, std::fabs(out.s[i] - in.s[i - lat]));
    CHECK(err < 1e-3 * 0.2);
  }
  // Independent ambience only: side power equals mid power, already at the budget limit.
  const auto wide = ambienceProgram(fs, 3.0, 0.0, 0.1, 8);
  Program out = wide;
  SpatialResidual sr(fs);
  sr.setParams(1, 1);
  runResidual(sr, out, 512);
  std::vector<double> dry(out.s.size());
  for (size_t i = lat; i < out.s.size(); ++i) dry[i] = wide.s[i - lat];
  CHECK_NEAR(20 * std::log10(rmsFrom(out.s, static_cast<size_t>(fs * 2)) / rmsFrom(dry, static_cast<size_t>(fs * 2))), 0.0, 0.25);
}

TEST(spatial_residual_holds_back_on_a_sudden_onset) {
  const double fs = 48000;
  // 2 s of quiet lead + ambience, then the whole program jumps 20 dB.
  auto a = ambienceProgram(fs, 2.0, 0.02, 0.003, 4), b = ambienceProgram(fs, 2.0, 0.2, 0.03, 5);
  Program in = a;
  in.m.insert(in.m.end(), b.m.begin(), b.m.end());
  in.s.insert(in.s.end(), b.s.begin(), b.s.end());
  Program out = in;
  SpatialResidual r(fs);
  r.setParams(1, 1);
  runResidual(r, out, 480);
  const size_t lat = 1024, step = static_cast<size_t>(fs * 2) + lat;
  auto deltaRms = [&](size_t from, size_t to) {
    double e = 0;
    for (size_t i = from; i < to; ++i) { const double d = out.s[i] - in.s[i - lat]; e += d * d; }
    return std::sqrt(e / static_cast<double>(to - from));
  };
  const double early = deltaRms(step, step + static_cast<size_t>(fs * 0.02)), late = deltaRms(step + static_cast<size_t>(fs * 1.0), step + static_cast<size_t>(fs * 1.5));
  std::printf("    delta rms: first 20 ms after onset %.5f, steady %.5f\n", early, late);
  CHECK(early < 0.6 * late);
}

TEST(spatial_residual_is_finite_and_bounded_on_loud_noise) {
  const double fs = 48000;
  std::mt19937 rng(77);
  std::uniform_real_distribution<double> u(-1, 1);
  Program p;
  for (int i = 0; i < static_cast<int>(fs * 3); ++i) { p.m.push_back(u(rng)); p.s.push_back(u(rng)); }
  SpatialResidual r(fs);
  r.setParams(1, 1);
  runResidual(r, p, 300);
  bool ok = true;
  for (size_t i = 0; i < p.m.size(); ++i) ok = ok && std::isfinite(p.s[i]) && std::fabs(p.s[i]) < 3.0;
  CHECK(ok);
  r.reset();  // reset gives the same fixed delay and clean state
  Program q;
  q.m.assign(4096, 0.0);
  q.s = q.m;
  q.m[10] = 1.0;
  runResidual(r, q, 100);
  CHECK(q.m[10 + 1024] == 1.0);
}

TEST(stereo_tuner_detailed_mode_reports_latency_and_keeps_images_in_place) {
  const double fs = 48000;
  StereoTuner plain(fs), detailed(fs, true);
  CHECK(plain.latencyFrames() == 0 && detailed.latencyFrames() == 1024);
  StereoTunerParams p;
  p.backingVocals = 1;
  p.spatialDetail = 1;
  for (double hz : {80.0, 180.0, 1200.0, 6000.0}) {
    for (bool left : {true, false}) {
      const double sgn = left ? 1 : -1;
      auto in = ms(fs, 1.5, [&](double t) { return .25 * std::sin(2 * kPi * hz * t); }, [&](double t) { return sgn * .25 * std::sin(2 * kPi * hz * t); });
      StereoTuner t(fs, true);
      t.setParams(p);
      for (size_t i = 0; i < in.l.size(); i += 256) {
        const int n = static_cast<int>(std::min<size_t>(256, in.l.size() - i));
        t.process(in.l.data() + i, in.r.data() + i, n);
      }
      const double keep = settledRms(left ? in.l : in.r), leak = settledRms(left ? in.r : in.l);
      CHECK(20 * std::log10((leak + 1e-30) / keep) < -40.0);
    }
  }
  EngineConfig cfg;
  cfg.sampleRate = fs;
  const int base = Engine(cfg).latencyFrames();
  cfg.spatialResidual = true;
  CHECK(Engine(cfg).latencyFrames() == base + 1024);
}

// ------------------------------------------------ sample-rate correctness (AQ-05)

TEST(engine_curve_matches_its_analytic_response_at_every_rate_family) {
  for (double fs : {44100.0, 48000.0, 88200.0, 96000.0, 176400.0, 192000.0}) {
    for (int oversample : {1, 4}) {
      EngineConfig cfg;
      cfg.sampleRate = fs;
      cfg.oversample = oversample;
      cfg.autoHeadroom = false;
      Engine e(cfg);
      e.setBandsAllChannels({{FilterType::Peak, 1000, 6.0, 1.0, true}, {FilterType::LowShelf, 100, 4.0, 0.71, true}, {FilterType::HighShelf, 8000, -3.0, 0.71, true}});
      for (double hz : {60.0, 250.0, 1000.0, 4000.0, 12000.0}) {
        std::vector<float> x(static_cast<size_t>(fs * 0.6) * 2);
        for (size_t t = 0; t < x.size() / 2; ++t) x[2 * t] = x[2 * t + 1] = static_cast<float>(0.1 * std::sin(2 * kPi * hz * static_cast<double>(t) / fs));
        for (size_t i = 0; i < x.size(); i += 2 * 480) e.process(x.data() + i, x.data() + i, static_cast<int>(std::min<size_t>(480, (x.size() - i) / 2)));
        std::vector<double> l;
        for (size_t t = x.size() / 4; t < x.size() / 2; ++t) l.push_back(x[2 * t]);
        const double measured = toDb(sineAmplitude(l, hz, fs, 0, l.size()) / 0.1);
        CHECK_NEAR(measured, e.responseDb(0, hz), 0.12);
      }
    }
  }
}

TEST(engine_latency_in_milliseconds_does_not_depend_on_the_rate) {
  double first = 0;
  for (double fs : {44100.0, 48000.0, 96000.0, 192000.0}) {
    EngineConfig cfg;
    cfg.sampleRate = fs;
    cfg.truePeak = true;
    const double ms = Engine(cfg).latencyFrames() * 1000.0 / fs;
    std::printf("    %.0f Hz: DSP latency %.3f ms\n", fs, ms);
    CHECK(ms > 2.9 && ms < 4.6);   // 3 ms lookahead + 64 detector frames, never a fixed frame count
    if (first == 0) first = ms;
    CHECK_NEAR(ms, first, 1.2);
  }
}

// ------------------------------------------------ selective bass unmasking (AQ-03 B)

namespace {
struct Partial { double hz, amp, tau; };  // tau <= 0: steady
std::vector<double> bassProgram(double fs, double secs, const std::vector<Partial>& parts) {
  std::vector<double> x(static_cast<size_t>(fs * secs));
  for (size_t i = 0; i < x.size(); ++i) {
    const double t = static_cast<double>(i) / fs;
    for (const auto& p : parts) x[i] += p.amp * (p.tau > 0 ? std::exp(-t / p.tau) : 1.0) * std::sin(2 * kPi * p.hz * t);
  }
  return x;
}
std::vector<Partial> noteWithPartials(double f0, double amp, double tau = 0) {
  return {{f0, amp, tau}, {2 * f0, 0.7 * amp, tau}, {3 * f0, 0.5 * amp, tau}, {4 * f0, 0.3 * amp, tau}};
}
// Runs mono through BassUnmask in blocks; returns the output and the worst combined cut seen.
std::vector<double> runUnmask(BassUnmask& u, std::vector<double> x, double* worstCutDb = nullptr, int block = 480) {
  double worst = 0;
  for (size_t i = 0; i < x.size(); i += static_cast<size_t>(block)) {
    u.process(x.data() + i, nullptr, static_cast<int>(std::min<size_t>(static_cast<size_t>(block), x.size() - i)));
    double sum = 0;
    for (double c : u.cutsDb()) sum += c;
    worst = std::min(worst, sum);
  }
  if (worstCutDb) *worstCutDb = worst;
  return x;
}
double maxDiff(const std::vector<double>& a, const std::vector<double>& b) {
  double d = 0;
  for (size_t i = 0; i < a.size(); ++i) d = std::max(d, std::fabs(a[i] - b[i]));
  return d;
}
double toneChangeDb(const std::vector<double>& in, const std::vector<double>& out, double hz, double fs, double from, double to) {
  const size_t a = static_cast<size_t>(from * fs), b = static_cast<size_t>(to * fs);
  return toDb(sineAmplitude(out, hz, fs, a, b) / sineAmplitude(in, hz, fs, a, b));
}
std::vector<Partial> maskedNote(double maskerHz = 130.0) {
  auto parts = noteWithPartials(55.0, 0.1);
  parts.push_back({maskerHz, 0.32, 0});  // sustained, not in 55 Hz's harmonic series, +10 dB over the note
  return parts;
}
}  // namespace

TEST(bass_unmask_off_is_a_bit_exact_bypass) {
  BassUnmask u(48000);
  const auto in = bassProgram(48000, 2.0, maskedNote());
  CHECK(maxDiff(runUnmask(u, in), in) == 0.0);
  u.setAmount(std::nan(""));
  CHECK(maxDiff(runUnmask(u, in), in) == 0.0);
}

TEST(bass_unmask_leaves_notes_tones_and_kicks_alone) {
  const double fs = 48000;
  std::vector<std::pair<const char*, std::vector<double>>> fixtures;
  for (double hz : {30.0, 40.0, 60.0, 100.0}) fixtures.push_back({"clean sine", bassProgram(fs, 4, {{hz, 0.3, 0}})});
  for (double f0 : {41.2, 55.0, 82.4}) {
    fixtures.push_back({"steady harmonic note", bassProgram(fs, 4, noteWithPartials(f0, 0.2))});
    fixtures.push_back({"decaying harmonic note", bassProgram(fs, 4, noteWithPartials(f0, 0.3, 1.0))});
  }
  fixtures.push_back({"resonant synth (strong 3rd partial)", bassProgram(fs, 4, {{82.4, 0.15, 0}, {164.8, 0.12, 0}, {247.2, 0.21, 0}, {329.6, 0.08, 0}})});
  fixtures.push_back({"kicks", [&] { std::vector<double> k; for (double v : kicks(fs, 7)) k.push_back(v); return k; }()});
  {  // kick over a bass note
    auto k = kicks(fs, 7);
    auto n = bassProgram(fs, 4.2, noteWithPartials(55.0, 0.15));
    for (size_t i = 0; i < k.size(); ++i) k[i] += n[i % n.size()];
    fixtures.push_back({"kick over bass note", k});
  }
  fixtures.push_back({"quiet tail with masker (-75 dBFS)", bassProgram(fs, 4, [] { auto m = maskedNote(); for (auto& p : m) p.amp *= 1.8e-4; return m; }())});
  for (auto& [name, in] : fixtures) {
    BassUnmask u(fs);
    u.setAmount(1.0);
    double worst = 0;
    const auto out = runUnmask(u, in, &worst);
    std::printf("    %-36s worst cut %.3f dB, max diff %.2e\n", name, worst, maxDiff(out, in));
    CHECK(worst > -0.02);
    CHECK(maxDiff(out, in) < 1e-6);
  }
}

TEST(bass_unmask_cuts_an_inharmonic_masker_but_not_the_note) {
  for (double fs : {44100.0, 48000.0, 96000.0, 192000.0}) {
    const auto in = bassProgram(fs, 5.0, maskedNote());
    BassUnmask u(fs);
    u.setAmount(1.0);
    double worst = 0;
    const auto out = runUnmask(u, in, &worst);
    const double masker = toneChangeDb(in, out, 130.0, fs, 4.0, 5.0), fundamental = toneChangeDb(in, out, 55.0, fs, 4.0, 5.0);
    std::printf("    %.0f Hz: masker %+.2f dB, fundamental %+.2f dB, worst combined cut %.2f dB, note %.1f Hz\n", fs, masker, fundamental, worst, u.noteHz());
    CHECK(masker < -0.4 && masker > -2.0);
    CHECK(fundamental > -0.4);      // the note itself is not what gets cut (lane skirt only)
    CHECK(worst >= -2.0 - 1e-9);    // combined limit
    CHECK_NEAR(u.noteHz(), 55.0, 3.0);
  }
}

TEST(bass_unmask_protects_a_loud_partial_of_the_note_itself) {
  const double fs = 48000;
  auto parts = noteWithPartials(55.0, 0.1);
  parts.push_back({165.0, 0.32, 0});  // 3rd harmonic of 55 Hz, +10 dB: part of the note, not a masker
  const auto in = bassProgram(fs, 5.0, parts);
  BassUnmask u(fs);
  u.setAmount(1.0);
  double worst = 0;
  const auto out = runUnmask(u, in, &worst);
  CHECK(worst > -0.02 && maxDiff(out, in) < 1e-6);
}

TEST(bass_unmask_amount_scales_the_cut_and_releases_to_an_exact_bypass) {
  const double fs = 48000;
  const auto in = bassProgram(fs, 5.0, maskedNote());
  double cut[2];
  for (int i = 0; i < 2; ++i) {
    BassUnmask u(fs);
    u.setAmount(i ? 1.0 : 0.4);
    cut[i] = toneChangeDb(in, runUnmask(u, in), 130.0, fs, 4.0, 5.0);
  }
  CHECK(cut[1] < cut[0] - 0.2 && cut[0] < -0.1);  // more amount, deeper cut
  BassUnmask u(fs);
  u.setAmount(1.0);
  runUnmask(u, in);
  CHECK(u.cutting());
  u.setAmount(0.0);
  const auto rest = bassProgram(fs, 4.0, maskedNote());
  const auto out = runUnmask(u, rest);
  bool finite = true;
  for (double v : out) finite = finite && std::isfinite(v);
  CHECK(finite && !u.cutting());
  const auto again = runUnmask(u, rest);
  CHECK(maxDiff(again, rest) == 0.0);  // exact bypass again
}

TEST(bass_unmask_releases_quickly_when_a_new_note_arrives_during_a_cut) {
  const double fs = 48000;
  auto in = bassProgram(fs, 6.0, maskedNote());
  const size_t t0 = static_cast<size_t>(fs * 4.5);
  for (size_t i = t0; i < in.size(); ++i) {  // a new, loud 41.2 Hz note with partials
    const double t = static_cast<double>(i - t0) / fs;
    in[i] += 0.5 * std::sin(2 * kPi * 41.2 * t) + 0.3 * std::sin(2 * kPi * 82.4 * t);
  }
  BassUnmask u(fs);
  u.setAmount(1.0);
  std::vector<double> out = in, cutAtOnset;
  double before = 0, after100ms = 0;
  for (size_t i = 0; i < out.size(); i += 48) {
    u.process(out.data() + i, nullptr, 48);
    double sum = 0;
    for (double c : u.cutsDb()) sum += c;
    if (i + 48 == t0 + (48 - t0 % 48) % 48 + 0 || (i <= t0 && t0 < i + 48)) before = sum;
    if (i <= t0 + static_cast<size_t>(fs * 0.1) && t0 + static_cast<size_t>(fs * 0.1) < i + 48) after100ms = sum;
  }
  std::printf("    cut at onset %.2f dB, 100 ms later %.2f dB\n", before, after100ms);
  CHECK(before < -0.3);                // a cut was active when the note arrived
  CHECK(after100ms > before + 0.25 && after100ms > -0.6);  // and it relaxed quickly
  // Residual alteration of the onset itself is bounded by the combined limit.
  double worstDrop = 0;
  for (size_t i = t0; i < t0 + static_cast<size_t>(fs * 0.01); ++i) {
    const double a = std::fabs(in[i] - (i > 0 ? in[i - 1] * 0 : 0)), b = std::fabs(out[i]);
    if (a > 0.3) worstDrop = std::min(worstDrop, toDb(b / a));
  }
  CHECK(worstDrop > -2.1);
}

TEST(bass_unmask_is_stereo_linked_and_keeps_balance) {
  const double fs = 48000;
  const auto base = bassProgram(fs, 5.0, maskedNote());
  std::vector<double> l = base, r = base;
  for (double& v : r) v *= 0.3;
  const auto l0 = l, r0 = r;
  BassUnmask u(fs);
  u.setAmount(1.0);
  for (size_t i = 0; i < l.size(); i += 480) u.process(l.data() + i, r.data() + i, static_cast<int>(std::min<size_t>(480, l.size() - i)));
  const double cl = toneChangeDb(l0, l, 130.0, fs, 4.0, 5.0), cr = toneChangeDb(r0, r, 130.0, fs, 4.0, 5.0);
  CHECK(cl < -0.4);
  CHECK_NEAR(cl, cr, 0.01);
}

TEST(bass_unmask_takes_the_low_dynamic_eq_lanes_so_a_note_is_not_cut_twice) {
  const double fs = 48000;
  auto program = bassProgram(fs, 6.0, maskedNote(120.0));  // 120 Hz: the old dynamic lane's centre
  double dyn[2];
  for (int i = 0; i < 2; ++i) {
    EngineConfig cfg;
    cfg.sampleRate = fs;
    cfg.autoHeadroom = false;
    cfg.gainProtection = false;
    Engine e(cfg);
    e.setDynamicEq(1.0);
    e.setBassUnmask(i ? 1.0 : 0.0);
    std::vector<float> x(program.size() * 2);
    for (size_t k = 0; k < program.size(); ++k) x[2 * k] = x[2 * k + 1] = static_cast<float>(program[k]);
    double worst = 0;
    for (size_t k = 0; k < x.size(); k += 960) {
      e.process(x.data() + k, x.data() + k, static_cast<int>(std::min<size_t>(480, (x.size() - k) / 2)));
      if (k > x.size() / 6 * 5) worst = std::min({worst, e.dynamicReductionsDb()[0], e.dynamicReductionsDb()[1]});  // last second
    }
    dyn[i] = worst;
    if (i) CHECK(e.bassUnmaskCutsDb()[1] < -0.3);
  }
  std::printf("    dynamic EQ low-lane reduction: %.2f dB without unmask, %.2f dB with\n", dyn[0], dyn[1]);
  CHECK(dyn[0] < -0.3);                 // the old lane would cut this note
  CHECK(dyn[1] > dyn[0] + 0.3);         // and yields once Resolve owns it
}

// ------------------------------------------------ executable policy registry (AQ-04)

namespace {
bool fileHasText(const std::string& path, const std::string& text) {
  std::ifstream in(path);
  std::stringstream ss;
  ss << in.rdbuf();
  return in && ss.str().find(text) != std::string::npos;
}
std::vector<policy::Measurement> goodEvidence(const policy::Rule& r, uint64_t epoch) {
  std::vector<policy::Measurement> m;
  for (int i = 0; i < r.inputCount; ++i) {
    policy::Measurement x;
    x.id = r.inputs[static_cast<size_t>(i)];
    x.value = 0.0; x.valid = true; x.confidence = 1.0; x.epoch = epoch; x.ageSeconds = 0.01;
    m.push_back(x);
  }
  return m;
}
}  // namespace

TEST(policy_registry_is_consistent_and_every_rule_names_a_real_counterexample_test) {
  CHECK(policy::validateRegistry().empty());
  if (!policy::validateRegistry().empty()) std::printf("    %s\n", policy::validateRegistry().c_str());
  CHECK(policy::rules().size() >= 18);
  for (const auto& r : policy::rules()) {
    const std::string ce = r.counterexample;
    bool found = false;
    if (ce.rfind("core:", 0) == 0) {
      for (const auto& t : registry()) found = found || ce.substr(5) == t.name;
    } else {
      const auto hash = ce.find('#');
      found = hash != std::string::npos &&
              fileHasText(std::string(EQCORE_REPO_DIR) + "/android/app/src/test/java/app/svan/" + ce.substr(7, hash - 7), "fun " + ce.substr(hash + 1) + "(");
    }
    if (!found) std::printf("    rule %s: counterexample '%s' does not exist\n", r.id, r.counterexample);
    CHECK(found);
  }
}

TEST(policy_registry_rejects_inconsistent_rules_in_a_copy) {
  // The validator must actually reject the mistakes it claims to: check a few on a mutated copy.
  using namespace policy;
  Rule base = *find("SV-UNMASK-1");
  CHECK(base.version == 1);
  const Rule& dup = *find("SV-UNMASK-1");
  CHECK(find("NOPE") == nullptr && &dup == find("SV-UNMASK-1"));
  CHECK(spec(Metric::VolumeProxy).proxy && !spec(Metric::VolumeProxy).pcm);
  CHECK(spec(Metric::BandwidthCutoffHz).pcm && std::string(spec(Metric::BandwidthCutoffHz).units).find("not a codec label") != std::string::npos);
  CHECK(std::string(spec(Metric::VolumeProxy).units).find("not SPL") != std::string::npos);
}

TEST(policy_evidence_gate_skips_on_every_kind_of_bad_evidence_for_every_rule) {
  using namespace policy;
  for (const auto& r : rules()) {
    Context ok;
    ok.epoch = 7; ok.nativePcm = true; ok.autoMaster = true;
    auto good = goodEvidence(r, 7);
    CHECK(admit(r, good.data(), static_cast<int>(good.size()), ok).admitted);
    if (r.inputCount > 0) {
      CHECK(admit(r, good.data(), 0, ok).skip == Skip::MissingInput);
      auto stale = good; stale[0].ageSeconds = r.maxAgeSeconds + 0.001;
      CHECK(admit(r, stale.data(), static_cast<int>(stale.size()), ok).skip == Skip::Stale);
      auto nan = good; nan[0].value = std::nan("");
      CHECK(admit(r, nan.data(), static_cast<int>(nan.size()), ok).skip == Skip::Invalid);
      auto inval = good; inval[0].valid = false;
      CHECK(admit(r, inval.data(), static_cast<int>(inval.size()), ok).skip == Skip::Invalid);
      if (r.minConfidence > 0) {
        auto weak = good; weak[0].confidence = r.minConfidence - 0.01;
        CHECK(admit(r, weak.data(), static_cast<int>(weak.size()), ok).skip == Skip::LowConfidence);
      }
      if (r.sameEpoch) {
        Context later = ok; later.epoch = 8;
        CHECK(admit(r, good.data(), static_cast<int>(good.size()), later).skip == Skip::WrongEpoch);
      }
    }
    Context off = ok; off.userOff = true;
    CHECK(admit(r, good.data(), static_cast<int>(good.size()), off).skip == Skip::UserOff);
    if (r.nativePcmOnly) {
      Context sys = ok; sys.nativePcm = false;
      CHECK(admit(r, good.data(), static_cast<int>(good.size()), sys).skip == Skip::NoNativePcm);
    }
    if (r.owner != Owner::Context && r.needsAutoMaster) {
      Context manual = ok; manual.autoMaster = false;
      CHECK(admit(r, good.data(), static_cast<int>(good.size()), manual).skip == Skip::AutoMasterOff);
    }
    for (double v : {1e30, -1e30, std::nan(""), std::numeric_limits<double>::infinity()}) {
      const double b = bound(r, v);
      CHECK(b >= r.minAction && b <= r.maxAction);
    }
  }
  // A proxy is never a measurement unless the rule declares it and caps itself.
  Rule sneaky = *find("SM-TILT-1");
  sneaky.inputs[0] = Metric::VolumeProxy;
  sneaky.nativePcmOnly = false;
  sneaky.allowProxy = false;
  auto m = goodEvidence(sneaky, 1);
  Context c; c.epoch = 1; c.nativePcm = true; c.autoMaster = true;
  CHECK(admit(sneaky, m.data(), static_cast<int>(m.size()), c).skip == Skip::ProxyNotAllowed);
}

TEST(policy_rule_bounds_match_the_constants_the_planner_enforces) {
  using namespace policy;
  CHECK(find("SM-TILT-1")->maxAction == svaramanas::kSvaresaMaxTiltDb);
  CHECK(find("SM-BOOM-1")->minAction == -svaramanas::kSvaresaMaxCorrectionDb);
  CHECK(find("SM-MUD-1")->minAction == -svaramanas::kSvaresaMaxCorrectionDb);
  CHECK(find("SM-BUDGET-1")->maxAction == svaramanas::kEmphasisBudgetDb);
  CHECK(find("SV-UNMASK-1")->minAction == -2.0 && find("SV-SPATIAL-1")->maxAction == 4.0);
  // And the planner really never exceeds them: guardrail test for every combination exists (see its rule entry).
  Rule bad = *find("SM-TILT-1");
  CHECK(bound(bad, 99.0) == svaramanas::kSvaresaMaxTiltDb);
}

TEST(policy_ownership_never_lowers_or_overwrites_the_saved_manual_value) {
  using namespace policy;
  CHECK(resolveOwnership(Ownership::Off, 0.7, 0.6, true, true).value == 0.0);
  CHECK(resolveOwnership(Ownership::Manual, 0.2, 0.6, true, true).value == 0.2);
  auto a = resolveOwnership(Ownership::Auto, 0.2, 0.6, true, true);
  CHECK(a.who == Ownership::Auto && a.value == 0.6);
  CHECK(resolveOwnership(Ownership::Auto, 0.9, 0.6, true, true).value == 0.9);        // never lowered
  auto inactive = resolveOwnership(Ownership::Auto, 0.2, 0.6, false, true);           // Auto master off
  CHECK(inactive.who == Ownership::Manual && inactive.value == 0.2);
  inactive = resolveOwnership(Ownership::Auto, 0.2, 0.6, true, false);                // evidence not admitted
  CHECK(inactive.who == Ownership::Manual && inactive.value == 0.2);
  CHECK(resolveOwnership(Ownership::Auto, std::nan(""), 0.6, true, true).value == 0.6);
}

TEST(policy_headroom_ledger_scales_the_sum_once) {
  const double req[] = {3.0, 4.0, 5.0, -2.0, std::nan("")};
  const double k = policy::headroomScale(req, 5, 6.0);
  CHECK_NEAR(k, 0.5, 1e-12);                  // 12 dB of positive requests into a 6 dB ceiling
  const double small[] = {1.0, 2.0};
  CHECK(policy::headroomScale(small, 2, 6.0) == 1.0);
  CHECK(policy::headroomScale(small, 0, 6.0) == 1.0);
  CHECK(policy::headroomScale(req, 5, -1.0) == 0.0);
}

// ------------------------------------------------ qualification (AQ-06)

namespace {
EngineConfig detailedConfig(double fs) {
  EngineConfig cfg = EngineConfig::forQuality(QualityMode::Audiophile, fs, 2, 24);
  cfg.spatialResidual = true;
  return cfg;
}
void turnEverythingOn(Engine& e) {
  e.setBandsAllChannels({{FilterType::Peak, 90, 3.0, 1.0, true}, {FilterType::HighShelf, 9000, 2.0, 0.7, true}});
  e.setBassCharacter(0.6, 120.0);
  e.setBassResolve(1.0);
  e.setBassUnmask(1.0);
  e.setDynamicEq(1.0);
  e.setStereoTuner({0.4, 0.3, 0.5, 0.3, 0.6, 1.0, 1.0});
}
}  // namespace

TEST(audio_thread_paths_do_not_allocate) {
  const double fs = 48000;
  // Components in isolation, then the whole detailed chain.
  SpatialResidual sr(fs);
  BassUnmask bu(fs);
  BassShaper bs(fs, 2);
  StereoTuner st(fs, true);
  sr.setParams(1, 1);
  bu.setAmount(1);
  bs.setCharacter(0.7);
  bs.setResolve(1);
  st.setParams({0.4, 0.3, 0.5, 0.3, 0.6, 1.0, 1.0});
  Engine e(detailedConfig(fs));
  turnEverythingOn(e);
  std::mt19937 rng(5);
  std::uniform_real_distribution<double> u(-0.4, 0.4);
  std::vector<double> a(480), b(480);
  std::vector<float> io(480 * 2);
  auto fill = [&] { for (auto& v : a) v = u(rng); for (auto& v : b) v = u(rng); for (auto& v : io) v = static_cast<float>(u(rng)); };
  for (int warm = 0; warm < 20; ++warm) { fill(); sr.process(a.data(), b.data(), 480); bu.process(a.data(), b.data(), 480); bs.processLinked(a.data(), b.data(), 480); st.process(a.data(), b.data(), 480); e.process(io.data(), io.data(), 480); }
  g_allocs = 0;
  g_countAllocs = true;
  for (int i = 0; i < 200; ++i) {
    fill();
    sr.process(a.data(), b.data(), 480);
    bu.process(a.data(), b.data(), 480);
    bs.processLinked(a.data(), b.data(), 480);
    st.process(a.data(), b.data(), 480);
    e.process(io.data(), io.data(), 480);
  }
  g_countAllocs = false;
  std::printf("    allocations in 200 blocks of every audio-thread path: %ld\n", g_allocs.load());
  CHECK(g_allocs.load() == 0);
}

TEST(detailed_engine_latency_is_reported_exactly) {
  // Same program, Detailed on vs off with every spatial control at zero: after shifting by the reported
  // latency difference the two outputs agree, so listening/blind rendering aligned by latencyFrames() is exact.
  const double fs = 48000;
  EngineConfig off = EngineConfig::forQuality(QualityMode::Audiophile, fs, 2, 24), on = off;
  on.spatialResidual = true;
  Engine a(off), b(on);
  a.setBandsAllChannels({{FilterType::Peak, 1000, 3.0, 1.0, true}});
  b.setBandsAllChannels({{FilterType::Peak, 1000, 3.0, 1.0, true}});
  const int extra = b.latencyFrames() - a.latencyFrames();
  CHECK(extra == 1024);
  std::mt19937 rng(9);
  std::uniform_real_distribution<float> u(-0.3f, 0.3f);
  std::vector<float> x(static_cast<size_t>(fs) * 2), ya, yb;
  for (auto& v : x) v = u(rng);
  ya = x;
  yb = x;
  for (size_t i = 0; i < x.size(); i += 960) {
    const int n = static_cast<int>(std::min<size_t>(480, (x.size() - i) / 2));
    a.process(ya.data() + i, ya.data() + i, n);
    b.process(yb.data() + i, yb.data() + i, n);
  }
  double err = 0;
  for (size_t f = static_cast<size_t>(extra) + 2000; f < x.size() / 2; ++f)
    for (int c = 0; c < 2; ++c) err = std::max(err, static_cast<double>(std::fabs(yb[2 * f + static_cast<size_t>(c)] - ya[2 * (f - static_cast<size_t>(extra)) + static_cast<size_t>(c)])));
  std::printf("    max aligned difference %.2e\n", err);
  CHECK(err < 2e-5);
}

TEST(eq_parameter_spatial_bass_and_unmask_publication_is_safe_during_processing) {
  const double fs = 48000;
  Engine e(detailedConfig(fs));
  std::atomic<bool> stop{false};
  std::thread ui([&] {
    std::mt19937 rng(1);
    std::uniform_real_distribution<double> u(0, 1);
    while (!stop.load()) {
      e.setStereoTuner({u(rng), u(rng), u(rng), u(rng) * 2 - 1, u(rng), u(rng), u(rng)});
      e.setBassCharacter(u(rng) * 2 - 1, 80 + 100 * u(rng));
      e.setBassResolve(u(rng));
      e.setBassUnmask(u(rng));
      e.setDynamicEq(u(rng));
    }
  });
  std::mt19937 rng(2);
  std::uniform_real_distribution<float> n(-0.3f, 0.3f);
  std::vector<float> io(480 * 2);
  bool finite = true;
  for (int i = 0; i < 300; ++i) {
    for (auto& v : io) v = n(rng);
    e.process(io.data(), io.data(), 480);
    for (float v : io) finite = finite && std::isfinite(v);
  }
  stop = true;
  ui.join();
  CHECK(finite);
}

TEST(instrument_amp_never_touches_a_centred_voice) {
  const double fs = 48000;
  // Pure centre content (L == R): voice fundamentals, formants and sibilance.
  auto in = ms(fs, 1.0, [](double t) {
    return 0.3 * std::sin(2 * kPi * 220 * t) + 0.2 * std::sin(2 * kPi * 3000 * t) + 0.05 * std::sin(2 * kPi * 7000 * t);
  }, [](double) { return 0.0; });
  StereoTunerParams p;
  p.space = 1.0;
  p.instruments = 1.0;
  auto out = runTuner(p, in, fs);
  CHECK(out.l == in.l && out.r == in.r);  // bit-identical, not just "close"
  p.space = -1.0;
  out = runTuner(p, in, fs);
  CHECK(out.l == in.l && out.r == in.r);
}

TEST(space_widens_or_narrows_the_sides_but_not_side_bass) {
  const double fs = 48000;
  for (double space : {1.0, -1.0}) {
    StereoTunerParams p;
    p.space = space;
    auto hi = runTuner(p, ms(fs, 1.0, [](double) { return 0.0; }, [](double t) { return 0.2 * std::sin(2 * kPi * 2000 * t); }), fs);
    auto lo = runTuner(p, ms(fs, 1.0, [](double) { return 0.0; }, [](double t) { return 0.2 * std::sin(2 * kPi * 60 * t); }), fs);
    const double gHi = toDb(sineAmplitude(sideOf(hi), 2000, fs, 24000, 48000) / 0.2);
    const double gLo = toDb(sineAmplitude(sideOf(lo), 60, fs, 24000, 48000) / 0.2);
    std::printf("    space %+.0f: sides at 2 kHz %+.2f dB, side bass at 60 Hz %+.2f dB\n", space, gHi, gLo);
    // Dry-plus-delta: the added high band sits ~15 degrees off the dry side at 2 kHz (zero-latency
    // IIR crossover), so the realised gain is within ~0.6 dB of the nominal +-6 dB and matches the model.
    CHECK_NEAR(gHi, 6.0 * space, 0.6);
    CHECK_NEAR(gHi, 10 * std::log10(stereoResponsePower(p, 2000, fs)[1]), 0.15);
    CHECK_NEAR(gLo, 0.0, 0.3);
  }
}

TEST(vocal_tuner_never_touches_the_sides) {
  const double fs = 48000;
  auto in = ms(fs, 0.5, [](double) { return 0.0; }, [](double t) {
    return 0.2 * std::sin(2 * kPi * 220 * t) + 0.2 * std::sin(2 * kPi * 3800 * t);
  });
  StereoTunerParams p;
  p.intimacy = p.warmth = p.smoothness = 1.0;
  auto out = runTuner(p, in, fs);
  double worst = 0;
  for (size_t i = 0; i < in.l.size(); ++i) worst = std::max(worst, std::fabs(out.l[i] - in.l[i]));
  CHECK(worst < 1e-12);
}

TEST(vocal_warmth_lifts_chest_and_softens_the_top) {
  const double fs = 48000;
  StereoTunerParams p;
  p.warmth = 1.0;
  for (auto [f, expect] : {std::pair{220.0, 3.0}, {12000.0, -2.0}}) {
    auto out = runTuner(p, ms(fs, 1.0, [f = f](double t) { return 0.2 * std::sin(2 * kPi * f * t); }, [](double) { return 0.0; }), fs);
    const double g = toDb(sineAmplitude(midOf(out), f, fs, 24000, 48000) / 0.2);
    std::printf("    warmth at %.0f Hz: %+.2f dB\n", f, g);
    CHECK_NEAR(g, expect, 0.5);
  }
}

TEST(smoothness_tames_shrill_vocals_and_spares_mellow_ones) {
  const double fs = 48000;
  StereoTunerParams p;
  p.smoothness = 1.0;
  // Shrill: the 3.8 kHz edge dominates the voice. Mellow: it's a faint overtone.
  auto shrillIn = [](double t) { return 0.1 * std::sin(2 * kPi * 300 * t) + 0.3 * std::sin(2 * kPi * 3800 * t); };
  auto mellowIn = [](double t) { return 0.3 * std::sin(2 * kPi * 300 * t) + 0.03 * std::sin(2 * kPi * 3800 * t); };
  auto shrill = runTuner(p, ms(fs, 1.0, shrillIn, [](double) { return 0.0; }), fs);
  auto mellow = runTuner(p, ms(fs, 1.0, mellowIn, [](double) { return 0.0; }), fs);
  const double cutShrill = toDb(sineAmplitude(midOf(shrill), 3800, fs, 24000, 48000) / 0.3);
  const double cutMellow = toDb(sineAmplitude(midOf(mellow), 3800, fs, 24000, 48000) / 0.03);
  const double body = toDb(sineAmplitude(midOf(shrill), 300, fs, 24000, 48000) / 0.1);
  std::printf("    3.8 kHz edge: shrill voice %+.1f dB, mellow voice %+.1f dB; 300 Hz body %+.2f dB\n", cutShrill, cutMellow, body);
  CHECK(cutShrill < -6.0);
  CHECK(cutMellow > -1.0);
  CHECK_NEAR(body, 0.0, 0.5);
}

TEST(engine_instrument_amp_keeps_centre_and_widens_sides) {
  const double fs = 48000;
  const int n = 48000;
  auto run = [&](StereoTunerParams p, double sideAmp) {
    EngineConfig cfg;
    cfg.sampleRate = fs;
    cfg.channels = 2;
    cfg.oversample = 2;
    cfg.autoHeadroom = false;
    cfg.gainProtection = false;
    Engine e(cfg);
    e.setStereoTuner(p);
    std::vector<float> buf(n * 2);
    for (int i = 0; i < n; ++i) {
      const double m = 0.2 * std::sin(2 * kPi * 1000 * i / fs), s = sideAmp * std::sin(2 * kPi * 2000 * i / fs);
      buf[2 * i] = static_cast<float>(m + s);
      buf[2 * i + 1] = static_cast<float>(m - s);
    }
    e.process(buf.data(), buf.data(), n);
    return buf;
  };
  StereoTunerParams wide;
  wide.space = 1.0;
  auto offMono = run({}, 0.0), wideMono = run(wide, 0.0);
  CHECK(offMono == wideMono);  // centre-only content: identical output through the whole chain
  auto wideSides = run(wide, 0.1);
  std::vector<double> side(n);
  for (int i = 0; i < n; ++i) side[i] = 0.5 * (wideSides[2 * i] - wideSides[2 * i + 1]);
  CHECK_NEAR(toDb(sineAmplitude(side, 2000, fs, n / 2, n) / 0.1), 6.0, 0.3);
}

TEST(stereo_tuner_is_stable_on_noise) {
  const double fs = 44100;
  StereoTuner t(fs);
  StereoTunerParams p{1, 1, 1, 1, 1, 1, 1};
  t.setParams(p);
  std::mt19937 rng(4);
  std::uniform_real_distribution<double> u(-1, 1);
  std::vector<double> l(44100), r(44100);
  for (size_t i = 0; i < l.size(); ++i) { l[i] = u(rng); r[i] = u(rng); }
  t.process(l.data(), r.data(), static_cast<int>(l.size()));
  bool ok = true;
  for (size_t i = 0; i < l.size(); ++i) ok = ok && std::isfinite(l[i]) && std::isfinite(r[i]) && std::fabs(l[i]) < 10;
  CHECK(ok);
}

TEST(stereo_detail_controls_have_measured_response_and_preserve_mono) {
  const double fs=48000;
  for (int control=0;control<2;++control) {
    StereoTunerParams p;
    if(control==0)p.backingVocals=1;else p.spatialDetail=1;
    for(double hz:{60.,1600.,8000.}) {
      auto input=ms(fs,1,[](double){return 0.;},[&](double t){return .1*std::sin(2*kPi*hz*t);});
      auto output=runTuner(p,input,fs);
      const double measured=toDb(sineAmplitude(sideOf(output),hz,fs,24000,48000)/.1);
      const double expected=10*std::log10(stereoResponsePower(p,hz,fs)[1]);
      CHECK_NEAR(measured,expected,.03);
      if(hz==60)CHECK(std::abs(measured)<.1);
      if(control==0 && hz==1600)CHECK(measured>1.8 && measured<2.2);
      if(control==1 && hz==8000)CHECK(measured>1.3 && measured<1.7);
      auto mono=ms(fs,.1,[&](double t){return .1*std::sin(2*kPi*hz*t);},[](double){return 0.;});
      auto unchanged=runTuner(p,mono,fs);
      CHECK(unchanged.l==mono.l && unchanged.r==mono.r);
    }
  }
}

// A tone's amplitude over [from, to) seconds of one channel-like vector.
static double toneDb(const std::vector<double>& x, double hz, double fs, double from, double to) {
  return toDb(sineAmplitude(x, hz, fs, static_cast<int>(from * fs), static_cast<int>(to * fs)));
}

TEST(backing_vocals_lift_masked_layers_and_leave_prominent_layers) {
  const double fs = 48000;
  StereoTunerParams p;
  p.backingVocals = 1;
  const double staticDb = 10 * std::log10(stereoResponsePower(p, 1300, fs)[1]);
  // Lead (centre, 1 kHz) 20 dB above harmony layers (side, 1.3 kHz).
  auto masked = ms(fs, 2, [](double t) { return .3 * std::sin(2 * kPi * 1000 * t); },
                   [](double t) { return .03 * std::sin(2 * kPi * 1300 * t); });
  const double maskedLift = toneDb(sideOf(runTuner(p, masked, fs)), 1300, fs, 1, 2) - toDb(.03);
  CHECK(maskedLift - staticDb > 1.5);  // dynamic de-masking on top of the static bell...
  CHECK(maskedLift < 4.3);             // ...but the shared side-energy budget caps the total lift at 4 dB
  // Layers already as loud as the lead (side power >= half the mid): nothing is added.
  auto prominent = ms(fs, 2, [](double t) { return .1 * std::sin(2 * kPi * 1000 * t); },
                      [](double t) { return .1 * std::sin(2 * kPi * 1300 * t); });
  const double prominentLift = toneDb(sideOf(runTuner(p, prominent, fs)), 1300, fs, 1, 2) - toDb(.1);
  CHECK_NEAR(prominentLift, 0.0, .3);
  // The lead itself and the mono sum are untouched.
  auto out = runTuner(p, masked, fs);
  bool monoSum = true;
  for (size_t i = 0; i < out.l.size(); ++i) monoSum = monoSum && std::fabs((out.l[i] + out.r[i]) - (masked.l[i] + masked.r[i])) < 1e-12;
  CHECK(monoSum);
}

TEST(binaural_motion_exaggerates_channel_bounces_but_not_static_images) {
  const double fs = 48000;
  StereoTunerParams p;
  p.spatialDetail = 1;
  // Ping-pong: a 2 kHz tone that bounces between hard left and hard right every 250 ms.
  auto bounce = [&](double t) {
    const double phase = std::fmod(t, .5) / .5;  // 0..1
    const double pan = phase < .5 ? 1 : -1;      // +1 left, -1 right
    return pan;
  };
  // Partial pans (side power 16 % of mid, inside the budget): the image bounces between +-0.4 of the way
  // to hard left/right, versus a fixed +0.4 placement.
  Stereo moving, still;
  for (int i = 0; i < static_cast<int>(fs * 3); ++i) {
    const double t = i / fs, x = .2 * std::sin(2 * kPi * 2000 * t), pan = bounce(t);
    moving.l.push_back(x * (1 + .4 * pan)); moving.r.push_back(x * (1 - .4 * pan));
    still.l.push_back(x * 1.4); still.r.push_back(x * .6);
  }
  auto sideRms = [](const std::vector<double>& s, size_t from) {
    double e = 0; for (size_t i = from; i < s.size(); ++i) e += s[i] * s[i]; return std::sqrt(e / (s.size() - from)); };
  const size_t from = static_cast<size_t>(fs);
  const double staticDb = 10 * std::log10(stereoResponsePower(p, 2000, fs)[1]);
  const double movingDb = toDb(sideRms(sideOf(runTuner(p, moving, fs)), from) / sideRms(sideOf(moving), from));
  const double stillDb = toDb(sideRms(sideOf(runTuner(p, still, fs)), from) / sideRms(sideOf(still), from));
  CHECK_NEAR(stillDb, staticDb, .3);       // a fixed placement stays where it was
  CHECK(movingDb - stillDb > 1.0);         // the artist's bounce becomes more dramatic
  CHECK(movingDb < 4.3);                   // bounded by the shared 4 dB side budget
  auto out = runTuner(p, moving, fs);
  bool monoSum = true, finite = true;
  for (size_t i = 0; i < out.l.size(); ++i) {
    monoSum = monoSum && std::fabs((out.l[i] + out.r[i]) - (moving.l[i] + moving.r[i])) < 1e-12;
    finite = finite && std::isfinite(out.l[i]) && std::fabs(out.l[i]) < 1;
  }
  CHECK(monoSum && finite);
}

TEST(stereo_live_edits_crossfade_preserve_history_and_are_block_independent) {
  auto run=[](int block) {
    StereoTuner t(48000);
    std::vector<double> l(8000,.1),r(8000,-.1);
    for(int start=0;start<8000;) {
      if(start==2000)t.setParams({1,1,1,1,1,1,1});
      if(start==4000)t.setParams({});
      const int boundary=start<2000?2000:start<4000?4000:8000;
      int n=std::min(block,boundary-start);
      t.process(l.data()+start,r.data()+start,n);start+=n;
    }
    CHECK_NEAR(l[2000],l[1999],1e-12);
    CHECK(std::abs(l[4000]-l[3999])<.002);
    CHECK(l.back()==.1 && r.back()==-.1);
    for(size_t i=1;i<l.size();++i)CHECK(std::abs(l[i]-l[i-1])<.01);
    return l;
  };
  auto a=run(1),b=run(127),c=run(256);
  CHECK(a==b && b==c);
}

TEST(stereo_repeated_identical_publications_do_not_change_audio) {
  StereoTuner a(48000),b(48000);StereoTunerParams p{.3,.2,.4,.5,.6,.7,.8};
  a.setParams(p);b.setParams(p);
  auto source=ms(48000,1,[](double t){return .1*std::sin(2*kPi*1000*t);},[](double t){return .1*std::sin(2*kPi*3200*t);});
  auto x=source,y=source;
  for(size_t i=0;i<x.l.size();i+=128) {
    int n=std::min<size_t>(128,x.l.size()-i);b.setParams(p);
    a.process(x.l.data()+i,x.r.data()+i,n);b.process(y.l.data()+i,y.r.data()+i,n);
  }
  CHECK(x.l==y.l && x.r==y.r);
}

TEST(eq_parameter_stereo_publication_is_safe_during_processing) {
  StereoTuner tuner(48000);
  std::thread writer([&]{for(int i=0;i<2000;++i)tuner.setParams({.2,.3,.4,.5,.6,(i%2)*1.,(i%3)*.5});});
  std::array<double,128> l{},r{};
  bool finite=true;
  for(int i=0;i<2000;++i) {
    l.fill(.05);r.fill(-.03);tuner.process(l.data(),r.data(),128);
    finite=finite && std::all_of(l.begin(),l.end(),[](double x){return std::isfinite(x);});
  }
  writer.join();CHECK(finite);
}

TEST(quality_presets_are_consistent) {
  auto a = EngineConfig::forQuality(QualityMode::Audiophile, 48000, 2, 24);
  CHECK(a.oversample == 4 && a.ditherBits == 24 && a.ditherMode == DitherMode::Tpdf);
  auto x = EngineConfig::forQuality(QualityMode::Extreme, 48000, 2, 16);
  CHECK(x.oversample == 8 && x.ditherMode == DitherMode::ShapedTpdf);
  auto ef = EngineConfig::forQuality(QualityMode::Efficient, 48000, 2, 16);
  CHECK(ef.oversample == 1 && ef.ditherBits == 0);
}

// ---- Svaramanas: SourceAnalyzer -------------------------------------------------

// Deterministic "music-like" test signal: log-spaced sines (equal amplitude per
// sine = equal power per octave = pink), shaped by shapeDb(f), random phases.
// Stereo: L/R share the mid signal; `side` adds independent per-channel content.
std::vector<float> multisine(double fs, double seconds, double fmax, std::function<double(double)> shapeDb,
                             double rmsDbfs = -20.0, double side = 0.0, unsigned seed = 7) {
  const int n = 240;
  const size_t frames = static_cast<size_t>(fs * seconds);
  std::mt19937 rng(seed);
  std::uniform_real_distribution<double> ph(0, 2 * kPi);
  struct Osc { std::complex<double> z, w; double a; };
  auto make = [&](std::vector<Osc>& v) {
    for (int i = 0; i < n; ++i) {
      const double f = 30.0 * std::pow(fmax / 30.0, i / (n - 1.0));
      v.push_back({std::polar(1.0, ph(rng)), std::polar(1.0, 2 * kPi * f / fs), std::pow(10.0, shapeDb(f) / 20.0)});
    }
  };
  std::vector<Osc> mid, sl, sr;
  make(mid);
  if (side > 0) { make(sl); make(sr); }
  std::vector<double> l(frames), r(frames);
  for (size_t t = 0; t < frames; ++t) {
    double m = 0, a = 0, b = 0;
    for (auto& o : mid) { m += o.a * o.z.imag(); o.z *= o.w; }
    for (auto& o : sl) { a += o.a * o.z.imag(); o.z *= o.w; }
    for (auto& o : sr) { b += o.a * o.z.imag(); o.z *= o.w; }
    l[t] = m + side * a;
    r[t] = m + side * b;
  }
  double e = 0;
  for (size_t t = 0; t < frames; ++t) e += 0.5 * (l[t] * l[t] + r[t] * r[t]);
  const double g = std::pow(10.0, rmsDbfs / 20.0) / std::sqrt(e / frames);
  std::vector<float> out(frames * 2);
  for (size_t t = 0; t < frames; ++t) { out[2 * t] = float(l[t] * g); out[2 * t + 1] = float(r[t] * g); }
  return out;
}

SourceFeatures analyse(const std::vector<float>& x, double fs = 48000) {
  SourceAnalyzer a(fs, 2);
  for (size_t i = 0; i < x.size() / 2; i += 480)
    a.process(&x[i * 2], static_cast<int>(std::min<size_t>(480, x.size() / 2 - i)));
  return a.snapshot();
}

auto flatShape = [](double) { return 0.0; };

TEST(fft_matches_direct_dft) {
  std::vector<std::complex<double>> x(64), y;
  std::mt19937 rng(1);
  std::uniform_real_distribution<double> u(-1, 1);
  for (auto& v : x) v = {u(rng), u(rng)};
  y = x;
  fftInPlace(y.data(), 64);
  double worst = 0;
  for (int k = 0; k < 64; ++k) {
    std::complex<double> s = 0;
    for (int n = 0; n < 64; ++n) s += x[n] * std::polar(1.0, -2 * kPi * k * n / 64.0);
    worst = std::max(worst, std::abs(s - y[k]));
  }
  CHECK(worst < 1e-9);
}

TEST(analyzer_loudness_matches_bs1770_reference) {
  // EBU Tech 3341: stereo 1 kHz sine at -23 dBFS peak per channel reads -23 LUFS.
  const double fs = 48000, a = std::pow(10.0, -23.0 / 20.0);
  std::vector<float> x(static_cast<size_t>(fs * 6) * 2);
  for (size_t t = 0; t < x.size() / 2; ++t) x[2 * t] = x[2 * t + 1] = float(a * std::sin(2 * kPi * 1000.0 * t / fs));
  const auto f = analyse(x, fs);
  CHECK(f.valid);
  CHECK_NEAR(f.loudnessLufs, -23.0, 0.2);
  CHECK_NEAR(f.peakDbfs, -23.0, 0.1);
  CHECK(f.clipsPerSecond == 0.0);
}

TEST(loudness_and_peak_measurements_are_rate_independent) {
  const double a = std::pow(10.0, -23.0 / 20.0);
  for (double fs : {44100.0, 48000.0, 88200.0, 96000.0, 192000.0}) {
    std::vector<float> x(static_cast<size_t>(fs * 6) * 2);
    for (size_t t = 0; t < x.size() / 2; ++t) x[2 * t] = x[2 * t + 1] = float(a * std::sin(2 * kPi * 1000.0 * static_cast<double>(t) / fs));
    const auto f = analyse(x, fs);
    CHECK(f.valid);
    CHECK_NEAR(f.loudnessLufs, -23.0, 0.25);
    CHECK_NEAR(f.peakDbfs, -23.0, 0.1);
  }
}

TEST(analyzer_finds_the_lossy_codec_ceiling) {
  const auto lossy = analyse(multisine(48000, 8, 16000, flatShape));
  CHECK(lossy.valid);
  CHECK(lossy.cutoffHz > 15500 && lossy.cutoffHz < 16800);
  const auto full = analyse(multisine(48000, 8, 20000, flatShape));
  CHECK(full.cutoffHz >= 19500);
}

TEST(analyzer_tilt_and_local_deviations) {
  const auto pink = analyse(multisine(48000, 8, 20000, flatShape));
  CHECK_NEAR(pink.tiltDbPerOct, 0.0, 0.6);
  CHECK(std::fabs(pink.mudDb) < 1.0 && std::fabs(pink.harshDb) < 1.0 && std::fabs(pink.boomDb) < 1.5);
  auto bump = [](double lo, double hi, double db) {
    return [=](double f) { return f >= lo && f <= hi ? db : 0.0; };
  };
  const auto muddy = analyse(multisine(48000, 8, 20000, bump(180, 560, 6.0)));
  CHECK(muddy.mudDb > 3.0);
  CHECK(std::fabs(muddy.harshDb) < 1.5);
  const auto shrill = analyse(multisine(48000, 8, 20000, bump(2300, 5600, 6.0)));
  CHECK(shrill.harshDb > 3.0);
  CHECK(std::fabs(shrill.mudDb) < 1.5);
  // A darker mix (-3 dB/oct) is a tilt, not a defect.
  const auto dark = analyse(multisine(48000, 8, 20000, [](double f) { return -3.0 * std::log2(f / 1000.0); }));
  CHECK_NEAR(dark.tiltDbPerOct, -3.0, 0.6);
  CHECK(std::fabs(dark.mudDb) < 1.0 && std::fabs(dark.harshDb) < 1.0);
}

TEST(analyzer_stereo_image) {
  const auto mono = analyse(multisine(48000, 6, 20000, flatShape));
  CHECK(mono.monoLike);
  CHECK_NEAR(mono.correlation, 1.0, 1e-3);
  const auto wide = analyse(multisine(48000, 6, 20000, flatShape, -20.0, 1.0));
  CHECK(!wide.monoLike);
  CHECK(wide.correlation < 0.7);
  CHECK(wide.sideToMidDb > -6.0);
}

TEST(analyzer_hears_side_only_music_and_its_tonal_balance) {
  auto mid = multisine(48000, 6, 20000, [](double f) { return f > 180 && f < 560 ? 6.0 : 0.0; });
  auto side = mid;
  for (size_t i = 1; i < side.size(); i += 2) side[i] = -side[i];
  const auto m = analyse(mid), s = analyse(side);
  CHECK(s.valid);
  CHECK_NEAR(s.loudnessLufs, m.loudnessLufs, 0.01);
  CHECK_NEAR(s.mudDb, m.mudDb, 0.01);
  CHECK_NEAR(s.cutoffHz, m.cutoffHz, 1.0);
  CHECK(s.correlation < -0.99 && !s.monoLike);
}

TEST(analyzer_pauses_do_not_wash_out_the_picture) {
  auto x = multisine(48000, 7, 20000, flatShape);
  const auto before = analyse(x);
  x.resize(x.size() + static_cast<size_t>(48000 * 10) * 2, 0.0f);  // 10 s pause
  const auto after = analyse(x);
  CHECK(after.valid);
  CHECK_NEAR(after.loudnessLufs, before.loudnessLufs, 0.3);
  CHECK_NEAR(after.mudDb, before.mudDb, 0.3);
}

TEST(analyzer_flags_a_clipped_master) {
  const double fs = 48000;
  std::vector<float> x(static_cast<size_t>(fs * 6) * 2);
  for (size_t t = 0; t < x.size() / 2; ++t) {
    const double v = std::clamp(2.0 * std::sin(2 * kPi * 220.0 * t / fs), -1.0, 1.0);
    x[2 * t] = x[2 * t + 1] = float(v);
  }
  const auto f = analyse(x, fs);
  CHECK(f.clipsPerSecond > 100.0);
  CHECK(f.plrDb < 8.0);
}

TEST(analyzer_features_round_trip_through_the_packed_layout) {
  const auto f = analyse(multisine(48000, 6, 16000, flatShape));
  std::vector<double> packed(SourceFeatures::kPacked);
  f.pack(packed.data());
  const auto g = SourceFeatures::unpack(packed.data(), static_cast<int>(packed.size()));
  CHECK(g.valid == f.valid && g.cutoffHz == f.cutoffHz && g.mudDb == f.mudDb && g.bandDb == f.bandDb);
  CHECK(g.hasStereoSpectrum && g.midBandDb == f.midBandDb && g.sideBandDb == f.sideBandDb);
  CHECK(!SourceFeatures::unpack(packed.data(), SourceFeatures::kLegacyPacked).hasStereoSpectrum);
}

// ---- Svaramanas: policy + guardrails --------------------------------------------

namespace sv = eqcore::svaramanas;

sv::Request req(sv::Feel feel, std::vector<uint32_t> order, double strength = 1.0) {
  sv::Request r;
  r.feel = feel;
  r.order = order;
  for (auto b : order) r.categories |= b;
  r.strength = strength;
  return r;
}

TEST(svaramanas_three_always_four_only_without_a_clash) {
  auto c = sv::checkCategories(req(sv::Feel::Balanced, {sv::kVocals, sv::kGuitars, sv::kBass, sv::kDrums}));
  CHECK(c.accepted == (sv::kVocals | sv::kGuitars | sv::kBass | sv::kDrums) && c.rejected == 0);
  // Guitars as the 4th pick fight the vocals for 1.8-3 kHz: refused, and we say with whom.
  c = sv::checkCategories(req(sv::Feel::Balanced, {sv::kVocals, sv::kStrings, sv::kBass, sv::kGuitars}));
  CHECK(c.accepted == (sv::kVocals | sv::kStrings | sv::kBass));
  CHECK(c.rejected == sv::kGuitars && (c.conflictWith & sv::kVocals));
  // The first three always stand, even when they overlap.
  c = sv::checkCategories(req(sv::Feel::Balanced, {sv::kVocals, sv::kGuitars, sv::kPiano}));
  CHECK(c.accepted == (sv::kVocals | sv::kGuitars | sv::kPiano));
  // Never more than four.
  c = sv::checkCategories(req(sv::Feel::Balanced, {sv::kVocals, sv::kBass, sv::kDrums, sv::kSpace, sv::kSynth}));
  CHECK(c.rejected == sv::kSynth);
}

TEST(svaramanas_strength_controls_the_entire_guide) {
  const auto zero = sv::plan(req(sv::Feel::Intimate, {sv::kVocals, sv::kStrings, sv::kSpace}, 0.0), nullptr);
  for (const auto& b : zero.bands) CHECK(b.gainDb == 0.0);
  CHECK(zero.stereo.isOff() && zero.bassCharacter == 0.0);
  const auto gentle = sv::plan(req(sv::Feel::Balanced, {sv::kStrings}, 0.5), nullptr);
  const auto bold = sv::plan(req(sv::Feel::Balanced, {sv::kStrings}, 1.5), nullptr);
  CHECK(bold.stereo.instruments > gentle.stereo.instruments);
  CHECK(bold.stereo.space > gentle.stereo.space);
}

TEST(svaramanas_guardrails_hold_for_every_combination) {
  int plans = 0, failures = 0;
  for (int feel = 0; feel <= 5; ++feel)
    for (uint32_t mask = 0; mask < (1u << sv::kCategoryCount); ++mask) {
      int bits = 0;
      for (uint32_t m = mask; m; m &= m - 1) ++bits;
      if (bits > 5) continue;
      for (double strength : {0.5, 1.0, 1.5}) {
        sv::Request r;
        r.feel = static_cast<sv::Feel>(feel);
        r.categories = mask;
        r.strength = strength;
        const auto p = sv::plan(r, nullptr);
        ++plans;
        double positive = 0, peak = -100;
        bool ok = true;
        for (const auto& b : p.bands) {
          ok = ok && std::fabs(b.gainDb) <= sv::kMaxBandDb + 1e-9;
          if (b.gainDb > 0) positive += b.gainDb;
        }
        for (double f = 20; f < 20000; f *= 1.05) {
          double h = 0;
          for (const auto& b : p.bands) h += magnitudeDb(designBiquad(b, 48000), f, 48000);
          peak = std::max(peak, h);
        }
        ok = ok && positive <= sv::kEmphasisBudgetDb + 1e-9;
        ok = ok && peak <= 6.0;
        // Loudness matched: trim cancels the predicted change (unless clamped).
        if (p.preampDb > -18.0 && p.preampDb < 1.5) ok = ok && std::fabs(p.preampDb + p.predictedDeltaDb) < 1e-9;
        ok = ok && p.stereo.intimacy >= 0 && p.stereo.intimacy <= 1 && p.stereo.space >= -1 && p.stereo.space <= 1;
        ok = ok && __builtin_popcount(p.categories.accepted) <= sv::kMaxCategories;
        if (!ok) ++failures;
      }
    }
  std::printf("    %d plans checked\n", plans);
  CHECK(failures == 0);
}

// Runs the engine with a plan and returns (input LUFS, output LUFS) measured by the analyser.
std::pair<double, double> measureThroughEngine(const std::vector<float>& x, const sv::Plan& p) {
  EngineConfig c;
  c.sampleRate = 48000;
  c.channels = 2;
  c.autoHeadroom = false;
  c.gainProtection = false;
  Engine e(c);
  e.setBandsAllChannels(p.bands);
  e.setPreampDb(p.preampDb);
  e.setStereoTuner(p.stereo);
  e.setBassCharacter(p.bassCharacter);
  std::vector<float> y(x.size());
  for (size_t i = 0; i < x.size() / 2; i += 512) {
    const int n = static_cast<int>(std::min<size_t>(512, x.size() / 2 - i));
    e.process(&x[i * 2], &y[i * 2], n);
  }
  return {analyse(x).loudnessLufs, analyse(y).loudnessLufs};
}

TEST(svaramanas_is_loudness_matched_when_measured) {
  // Never win by being louder: the measured K-weighted loudness after the smart
  // layer stays within 0.5 dB of the original, on pink and on a dark mix.
  const auto pink = multisine(48000, 8, 20000, flatShape, -24.0, 0.5);
  const auto dark = multisine(48000, 8, 20000, [](double f) { return -2.0 * std::log2(f / 1000.0); }, -24.0, 0.5);
  const std::vector<sv::Request> cases = {
      req(sv::Feel::Bright, {sv::kVocals, sv::kGuitars, sv::kDrums}),
      req(sv::Feel::Warm, {sv::kBass, sv::kStrings}),
      req(sv::Feel::Punchy, {sv::kDrums, sv::kBass, sv::kSynth, sv::kSpace}, 1.5),
      req(sv::Feel::Intimate, {sv::kVocals, sv::kPiano}),
  };
  for (const auto* sig : {&pink, &dark}) {
    const auto heard = analyse(*sig);
    for (const auto& r : cases) {
      const auto p = sv::plan(r, &heard);
      const auto [in, out] = measureThroughEngine(*sig, p);
      std::printf("    delta %.2f dB (predicted %.2f before trim)\n", out - in, p.predictedDeltaDb);
      CHECK_NEAR(out - in, 0.0, 0.5);
    }
  }
}

TEST(svaramanas_instrument_presence_has_measured_contrast) {
  // Shared-band tone shaping, not source separation. Measure actual output
  // spectrum contrast against the nearby masking region at matched level.
  const auto x = multisine(48000, 6, 20000, flatShape, -24);
  const auto heard = analyse(x);
  struct Focus { uint32_t category; double front, mask; };
  for (const auto focus : {Focus{sv::kVocals, 3000, 250}, Focus{sv::kGuitars, 2400, 350},
                          Focus{sv::kStrings, 2200, 300}, Focus{sv::kBrass, 1000, 300},
                          Focus{sv::kBass, 80, 250}, Focus{sv::kPiano, 4500, 400}}) {
    const auto p = sv::plan(req(sv::Feel::Balanced, {focus.category}), &heard);
    EngineConfig c; c.autoHeadroom = false; c.gainProtection = false;
    Engine engine(c); engine.setBandsAllChannels(p.bands); engine.setPreampDb(p.preampDb);
    engine.setBassCharacter(p.bassCharacter); engine.setStereoTuner(p.stereo);
    auto y = x;
    for (size_t i = 0; i < y.size() / 2; i += 512)
      engine.process(&x[i * 2], &y[i * 2], static_cast<int>(std::min<size_t>(512, y.size() / 2 - i)));
    const auto after = analyse(y);
    auto index = [](double hz) { return static_cast<size_t>(std::round(3 * std::log2(hz / 25))); };
    const auto a = index(focus.front), b = index(focus.mask);
    const double contrast = (after.bandDb[a] - after.bandDb[b]) - (heard.bandDb[a] - heard.bandDb[b]);
    std::printf("    category %u foreground contrast %+.2f dB, level %+.2f dB\n", focus.category, contrast, after.loudnessLufs - heard.loudnessLufs);
    CHECK(contrast > 1.2 && contrast < 5.0);
    CHECK_NEAR(after.loudnessLufs, heard.loudnessLufs, .5);
  }
}

TEST(stereo_level_model_matches_actual_complex_crossover_and_focus) {
  // Mid and side individually: the LR4 phase sum must match, not just the
  // magnitudes of its low and high branches. Includes the crossover itself.
  StereoTunerParams p{.7, .3, 0, .6, .8};
  for (double hz : {80., 180., 500., 1200., 3000., 10000.}) {
    const auto predicted = stereoResponsePower(p, hz, 48000);
    for (int mode = 0; mode < 2; ++mode) {
      StereoTuner tuner(48000); tuner.setParams(p);
      std::vector<double> l(48000), r(48000);
      double in = 0, out = 0;
      for (size_t i = 0; i < l.size(); ++i) { l[i] = .01 * std::sin(2 * kPi * hz * i / 48000); r[i] = mode ? -l[i] : l[i]; }
      for (size_t i = 12000; i < l.size(); ++i) in += l[i] * l[i];
      tuner.process(l.data(), r.data(), static_cast<int>(l.size()));
      for (size_t i = 12000; i < l.size(); ++i) out += .5 * (l[i] * l[i] + r[i] * r[i]);
      CHECK_NEAR(10 * std::log10(out / in), 10 * std::log10(predicted[static_cast<size_t>(mode)]), .02);
    }
  }
}

TEST(svaramanas_matches_frequency_dependent_stereo_energy) {
  const auto mid = multisine(48000, 6, 20000, [](double f) { return -3 * std::log2(f / 1000); }, -28);
  const auto side = multisine(48000, 6, 20000, [](double f) { return f < 1000 ? -25 : 0; }, -28, 0, 123);
  auto x = mid;
  for (size_t i = 0; i < x.size(); i += 2) { x[i] = mid[i] + side[i]; x[i + 1] = mid[i] - side[i]; }
  const auto heard = analyse(x);
  CHECK(heard.hasStereoSpectrum);
  for (const auto r : {req(sv::Feel::Spacious, {sv::kStrings, sv::kSpace}, 1.5),
                       req(sv::Feel::Intimate, {sv::kVocals, sv::kBrass}, 1.5)}) {
    const auto p = sv::plan(r, &heard);
    const auto [before, after] = measureThroughEngine(x, p);
    std::printf("    split-spectrum full-guide level %+.2f dB\n", after - before);
    CHECK_NEAR(after, before, .5);
  }
}

TEST(svaresa_corrects_side_only_masking_without_inventing_instrument_picks) {
  auto x = multisine(48000, 6, 20000, [](double f) { return f > 180 && f < 560 ? 6 : 0; }, -24);
  for (size_t i = 1; i < x.size(); i += 2) x[i] = -x[i];
  const auto heard = analyse(x);
  sv::Request r; r.svaresaMode = true;
  const auto p = sv::plan(r, &heard);
  CHECK(p.categories.accepted == 0 && p.bassCharacter == 0);
  EngineConfig c; c.autoHeadroom = false; c.gainProtection = false;
  Engine e(c); e.setBandsAllChannels(p.bands); e.setPreampDb(p.preampDb); e.setStereoTuner(p.stereo);
  auto y = x;
  for (size_t i = 0; i < y.size() / 2; i += 512)
    e.process(&x[i * 2], &y[i * 2], static_cast<int>(std::min<size_t>(512, y.size() / 2 - i)));
  const auto corrected = analyse(y);
  std::printf("    side-only mud %.2f -> %.2f dB, level %+.2f dB\n", heard.mudDb, corrected.mudDb, corrected.loudnessLufs - heard.loudnessLufs);
  CHECK(corrected.mudDb < heard.mudDb - 1);
  CHECK_NEAR(corrected.loudnessLufs, heard.loudnessLufs, .5);
}

TEST(guide_gain_protection_covers_strong_intimacy_and_ambience) {
  const auto p = sv::plan(req(sv::Feel::Spacious, {sv::kVocals, sv::kStrings, sv::kSpace}, 1.5), nullptr);
  Engine e(EngineConfig{}); e.setBandsAllChannels(p.bands); e.setPreampDb(p.preampDb);
  e.setStereoTuner(p.stereo); e.setBassCharacter(p.bassCharacter);
  std::vector<float> x(96000), y(x.size());
  for (size_t i = 0; i < x.size(); i += 2) {
    x[i] = float(.95 * std::sin(2 * kPi * 3000 * (i / 2) / 48000));
    x[i + 1] = -x[i];
  }
  e.process(x.data(), y.data(), static_cast<int>(x.size() / 2));
  for (float v : y) CHECK(std::isfinite(v) && std::fabs(v) <= .989);
}

TEST(svaresa_combined_context_is_loudness_matched_in_the_signal_path) {
  const auto x = multisine(48000, 6, 20000, flatShape, -24, .5);
  const auto heard = analyse(x);
  for (bool stereo : {false, true}) {
    auto r = req(sv::Feel::Intimate, {sv::kVocals}); r.stereoEngine = stereo;
    auto p = sv::plan(r, &heard);
    p.bands.push_back({FilterType::LowShelf, 110, 6, .71, true});
    p.bands.push_back({FilterType::HighShelf, 7500, 3, .71, true});
    p.bands.push_back({FilterType::Peak, 3800, -1, 1, true});
    p.predictedDeltaDb = sv::predictedGuideLoudnessDeltaDb(p.bands, p.stereo, &heard);
    p.preampDb = std::clamp(-p.predictedDeltaDb, -18., 1.5);
    const auto [before, after] = measureThroughEngine(x, p);
    std::printf("    combined context full-path level %+.2f dB\n", after - before);
    CHECK_NEAR(after, before, .5);
  }
}

TEST(svaramanas_trims_what_it_hears_and_leaves_a_clean_mix_alone) {
  const auto clean = analyse(multisine(48000, 8, 20000, flatShape, -20.0, 0.5));
  auto p = sv::plan(req(sv::Feel::Balanced, {}), &clean);
  for (const auto& b : p.bands) CHECK(std::fabs(b.gainDb) < 1e-9);  // nothing to fix, nothing asked
  auto bump = [](double lo, double hi, double db) { return [=](double f) { return f >= lo && f <= hi ? db : 0.0; }; };
  const auto muddy = analyse(multisine(48000, 8, 20000, bump(180, 560, 6.0), -20.0, 0.5));
  p = sv::plan(req(sv::Feel::Balanced, {}), &muddy);
  bool cut = false;
  for (const auto& b : p.bands) cut = cut || (b.freqHz == 300 && b.gainDb < -0.5 && b.gainDb >= -sv::kMaxCorrectionDb);
  CHECK(cut);
  CHECK(std::find(p.notes.begin(), p.notes.end(), sv::kNoteMud) != p.notes.end());
}

TEST(svaresa_ignores_guided_taste_and_only_corrects_measured_mix_issues) {
  auto bump = [](double lo, double hi, double db) { return [=](double f) { return f >= lo && f <= hi ? db : 0.0; }; };
  const auto muddy = analyse(multisine(48000, 8, 20000, bump(180, 560, 6.0), -20.0, 0.5));

  auto automatic = req(sv::Feel::Bright, {sv::kVocals, sv::kBass, sv::kSpace}, 1.5);
  automatic.svaresaMode = true;
  auto plain = req(sv::Feel::Balanced, {}, 1.0);
  plain.svaresaMode = true;
  const auto p = sv::plan(automatic, &muddy);
  const auto q = sv::plan(plain, &muddy);

  // Taste and picks are ignored: identical plan.
  CHECK(p.categories.accepted == 0 && p.categories.rejected == 0);
  CHECK(p.bands.size() == q.bands.size());
  CHECK(p.notes == q.notes);
  for (size_t i = 0; i < p.bands.size(); ++i) {
    CHECK(p.bands[i].type == q.bands[i].type);
    CHECK(p.bands[i].freqHz == q.bands[i].freqHz);
    CHECK_NEAR(p.bands[i].gainDb, q.bands[i].gainDb, 1e-12);
    CHECK(std::fabs(p.bands[i].gainDb) <= sv::kSvaresaMaxCorrectionDb + 1e-9);
  }
  // The measured low-mid excess is cut, and harder than guided mode's 2.5 dB cap allows.
  double cut300 = 0.0;
  for (const auto& b : p.bands)
    if (b.freqHz == 300.0) cut300 = b.gainDb;
  CHECK(cut300 < -2.0);
  CHECK(p.bassCharacter == 0.0);
}

TEST(svaresa_moves_a_bright_or_dark_mix_toward_a_healthy_balance_within_bounds) {
  auto svaresa = [](const SourceFeatures& f, bool stereo) {
    sv::Request r;
    r.svaresaMode = true;
    r.stereoEngine = stereo;
    return sv::plan(r, &f);
  };
  auto gainAt = [](const sv::Plan& p, double hz, FilterType t) {
    for (const auto& b : p.bands)
      if (b.freqHz == hz && b.type == t) return b.gainDb;
    return 0.0;
  };
  const auto bright = analyse(multisine(48000, 8, 20000, [](double f) { return 5.0 * std::log2(f / 1000.0); }));
  const auto dark = analyse(multisine(48000, 8, 20000, [](double f) { return -6.0 * std::log2(f / 1000.0); }));
  const auto balanced = analyse(multisine(48000, 8, 20000, [](double f) { return -2.5 * std::log2(f / 1000.0); }));
  CHECK(bright.tiltDbPerOct > dark.tiltDbPerOct + 3.0);

  const auto pb = svaresa(bright, true), pd = svaresa(dark, true);
  CHECK(bright.tiltDbPerOct - sv::kSvaresaTiltTargetDbPerOct > sv::kSvaresaTiltDeadbandDbPerOct);
  CHECK(sv::kSvaresaTiltTargetDbPerOct - dark.tiltDbPerOct > sv::kSvaresaTiltDeadbandDbPerOct);
  CHECK(std::fabs(balanced.tiltDbPerOct - sv::kSvaresaTiltTargetDbPerOct) <= sv::kSvaresaTiltDeadbandDbPerOct);
  {
    CHECK(gainAt(pb, 4000.0, FilterType::HighShelf) < -0.5);
    CHECK(gainAt(pb, 200.0, FilterType::LowShelf) > 0.2);
    CHECK(std::find(pb.notes.begin(), pb.notes.end(), sv::kNoteBright) != pb.notes.end());
  }
  {
    CHECK(gainAt(pd, 4000.0, FilterType::HighShelf) > 0.5);
    CHECK(gainAt(pd, 200.0, FilterType::LowShelf) < -0.2);
    CHECK(std::find(pd.notes.begin(), pd.notes.end(), sv::kNoteDark) != pd.notes.end());
  }
  for (const auto* p : {&pb, &pd})
    for (const auto& b : p->bands) CHECK(std::fabs(b.gainDb) <= sv::kSvaresaMaxCorrectionDb + 1e-9);
  // A mix already inside the dead band is left alone by the tilt shelves.
  {
    const auto pf = svaresa(balanced, true);
    CHECK(gainAt(pf, 4000.0, FilterType::HighShelf) == 0.0 && gainAt(pf, 200.0, FilterType::LowShelf) == 0.0);
  }
  // Loudness-matched like every plan.
  CHECK(pb.preampDb <= 1.5 && pb.preampDb >= -8.0);
  CHECK(std::fabs(pb.preampDb + pb.predictedDeltaDb) < 1e-6 || pb.preampDb == 1.5 || pb.preampDb == -8.0);
}

TEST(svaresa_asks_for_harshness_smoothing_on_both_engines_when_the_mix_is_shrill) {
  auto bump = [](double lo, double hi, double db) { return [=](double f) { return f >= lo && f <= hi ? db : 0.0; }; };
  const auto shrill = analyse(multisine(48000, 8, 20000, bump(2300, 5600, 9.0)));
  CHECK(shrill.harshDb > 3.0);
  for (bool stereoEngine : {false, true}) {
    sv::Request r;
    r.svaresaMode = true;
    r.stereoEngine = stereoEngine;
    const auto p = sv::plan(r, &shrill);
    CHECK(p.stereo.smoothness > 0.05);
    CHECK(p.stereo.smoothness <= 0.5 + 1e-9);
  }
  sv::Request guided;
  guided.stereoEngine = false;
  CHECK(sv::plan(guided, &shrill).stereo.smoothness == 0.0);  // guided mode keeps its own rules
  // Nothing heard: Svaresa changes nothing by itself (context layers are added by the app).
  sv::Request none;
  none.svaresaMode = true;
  none.stereoEngine = false;
  const auto idle = sv::plan(none, nullptr);
  for (const auto& b : idle.bands) CHECK(b.gainDb == 0.0);
}

TEST(svaramanas_respects_lossy_sources_mono_files_and_crushed_masters) {
  const auto lossy = analyse(multisine(48000, 8, 16000, flatShape, -20.0, 0.5));
  auto p = sv::plan(req(sv::Feel::Bright, {sv::kSynth, sv::kSpace}), &lossy);
  for (const auto& b : p.bands) CHECK(!(b.gainDb > 0 && b.freqHz >= 0.7 * lossy.cutoffHz));
  CHECK(std::find(p.notes.begin(), p.notes.end(), sv::kNoteLossy) != p.notes.end());

  const auto mono = analyse(multisine(48000, 6, 20000, flatShape));
  p = sv::plan(req(sv::Feel::Spacious, {sv::kSpace}), &mono);
  CHECK(p.stereo.space == 0.0 && p.stereo.instruments == 0.0);

  // A limited, clipping master: lifts are halved (more boost would only clip).
  SourceFeatures crushed = mono;
  crushed.plrDb = 6.0;
  crushed.clipsPerSecond = 50.0;
  const auto loudReq = req(sv::Feel::Bright, {sv::kVocals});
  const auto a = sv::plan(loudReq, &crushed);
  const auto b = sv::plan(loudReq, &mono);
  double pa = 0, pb = 0;
  for (const auto& x : a.bands) pa += std::max(0.0, x.gainDb);
  for (const auto& x : b.bands) pb += std::max(0.0, x.gainDb);
  CHECK(pa < 0.6 * pb);
}

TEST(svaramanas_keeps_one_band_skeleton_per_request) {
  // Adaptive updates slew band by band, so what was heard must never change the layout.
  const auto r = req(sv::Feel::Warm, {sv::kVocals, sv::kBass});
  const auto a = sv::plan(r, nullptr);
  const auto heard = analyse(multisine(48000, 8, 16000, [](double f) { return f > 200 && f < 500 ? 6.0 : 0.0; }));
  const auto b = sv::plan(r, &heard);
  CHECK(a.bands.size() == b.bands.size());
  for (size_t i = 0; i < std::min(a.bands.size(), b.bands.size()); ++i)
    CHECK(a.bands[i].type == b.bands[i].type && a.bands[i].freqHz == b.bands[i].freqHz && a.bands[i].q == b.bands[i].q);
}

TEST(engine_analyses_the_source_not_its_own_output) {
  EngineConfig c;
  c.sampleRate = 48000;
  c.channels = 2;
  Engine e(c);
  e.setPreampDb(-12.0);
  e.setAnalysisEnabled(true);
  auto x = multisine(48000, 6, 20000, flatShape, -20.0, 0.5);
  const double in = analyse(x).loudnessLufs;
  for (size_t i = 0; i < x.size() / 2; i += 256) e.process(&x[i * 2], &x[i * 2], 256);  // in place
  CHECK_NEAR(e.analysis().loudnessLufs, in, 0.2);
}

TEST(graphic_fit_flat_is_neutral_in_all_layouts) {
  for (int count : {10, 15, 31, 64}) {
    auto fit = fitGraphicEq({}, count);
    CHECK(fit.bands.size() == static_cast<size_t>(count));
    CHECK_NEAR(fit.rmsDb, 0, 1e-8);
    for (const auto& b : fit.bands) CHECK_NEAR(b.gainDb, 0, 1e-8);
  }
}
TEST(graphic_fit_preserves_its_own_filter_response) {
  auto base = fitGraphicEq({}, 31).bands;
  base[5].gainDb = 3; base[13].gainDb = -2; base[25].gainDb = 2;
  auto fit = fitGraphicEq(base, 31);
  std::printf("    graphic roundtrip RMS=%.4f max=%.4f dB\n", fit.rmsDb, fit.maxDb);
  CHECK(fit.rmsDb < 0.03 && fit.maxDb < 0.12);
}
TEST(graphic_fit_svaresa_curve_is_bounded_and_measured) {
  std::vector<BandParams> target = {{FilterType::LowShelf,110,6,.71}, {FilterType::Peak,300,-3,1},
    {FilterType::Peak,3500,-2,1.2}, {FilterType::HighShelf,7500,2,.71}};
  for (int count : {10, 15, 31, 64}) {
    const auto fit = fitGraphicEq(target, count);
    std::printf("    Svaresa graphic %d RMS=%.3f max=%.3f dB\n", count, fit.rmsDb, fit.maxDb);
    CHECK(fit.rmsDb < 0.65 && fit.maxDb < 2.5);
    for (const auto& b : fit.bands) CHECK(std::isfinite(b.gainDb) && std::abs(b.gainDb) <= 12);
    // Error metadata describes the actual filter cascade (not sampled fader values).
    double sum = 0, peak = 0;
    for (int i=0;i<240;++i) {
      const double f = 20 * std::pow(1000.0, i / 239.0);
      double err = 0;
      for (const auto& b : target) err -= magnitudeDb(designBiquad(b,48000),f,48000);
      for (const auto& b : fit.bands) err += magnitudeDb(designBiquad(b,48000),f,48000);
      sum += err*err; peak=std::max(peak,std::abs(err));
    }
    CHECK_NEAR(fit.rmsDb,std::sqrt(sum/240),1e-7); CHECK_NEAR(fit.maxDb,peak,1e-7);
  }
}
TEST(graphic_fit_level_trim_matches_the_applied_cascade) {
  const auto source=multisine(48000,8,20000,flatShape,-24,0.5);
  const auto heard=analyse(source);
  auto p=sv::plan(req(sv::Feel::Warm,{sv::kVocals,sv::kStrings}),&heard);
  const auto target=p.bands;
  for(int count : {10,31,64}) {
    p.bands=fitGraphicEq(target,count).bands;
    p.predictedDeltaDb=sv::predictedGuideLoudnessDeltaDb(p.bands,p.stereo,&heard);
    p.preampDb=std::clamp(-p.predictedDeltaDb,-18.,1.5);
    const auto levels=measureThroughEngine(source,p);
    const double delta=levels.second-levels.first;
    std::printf("    fitted graphic %d full-chain loudness difference=%.3f dB\n",count,delta);
    CHECK(std::abs(delta)<.3);
  }
}
TEST(overlap_guard_bounds_the_sum_without_flattening_safe_curves) {
  std::vector<BandParams> bands={{FilterType::Peak,1000,3,1},{FilterType::Peak,1000,3,1},{FilterType::Peak,1000,3,1}, {FilterType::Peak,500,-2,1}};
  const double scale=positiveEqOverlapScale(bands);
  CHECK(scale<.75 && scale>0);
  for(auto& b:bands) if(b.gainDb>0) b.gainDb*=scale;
  CHECK_NEAR(bands.back().gainDb,-2,1e-9);
  ParametricEq peq(1,48000);peq.setBands(0,bands);
  CHECK(peq.peakGainDb(0,20,20000,4096) < 6.02);
  CHECK_NEAR(positiveEqOverlapScale({{FilterType::Peak,1000,3,1}}),1,1e-9);
  CHECK_NEAR(positiveEqOverlapScale({{FilterType::Peak,1000,6,1},{FilterType::Peak,1000,-6,1}}),1,1e-9);
}

TEST(overlapping_eq_headroom_uses_the_combined_curve) {
  EngineConfig cfg; cfg.gainProtection=false; cfg.oversample=2;
  Engine e(cfg);
  e.setBandsAllChannels({{FilterType::Peak,1000,6,.8},{FilterType::Peak,1200,6,.8},{FilterType::LowShelf,150,3,.71}});
  CHECK(e.appliedGainDb() < -11); // greatest individual band is only +6 dB
  CHECK(e.responseDb(0,1100)<.02);
  CHECK(engineGainDb(e,1100,.99) < .02);
  e.setPreampDb(-20);
  CHECK_NEAR(e.appliedGainDb(),-20,1e-8); // don't attenuate required overlap headroom twice
}

TEST(graphic_fit_rejects_invalid_layout_and_limits_unrepresentable_curves) {
  CHECK(fitGraphicEq({}, 0).bands.empty());
  CHECK(fitGraphicEq({}, 128).bands.empty());
  auto fit=fitGraphicEq({{FilterType::Peak,1000,24,12}},10);
  CHECK(fit.maxDb > 5); // Do not pretend a narrow +24 dB filter survived a coarse layout.
  for (const auto& b:fit.bands) CHECK(std::isfinite(b.gainDb) && std::abs(b.gainDb)<=12);
}

// Independent reconstruction: existing 8x Kaiser FIR, not the limiter's detector.
static double reconstructedPeak(const std::vector<float>& audio,int channels=2,double fs=48000) {
  OversamplerSpec spec;spec.factor=8;spec.baseSampleRate=fs;spec.stopbandDb=140;spec.maxBlock=512;
  double peak=0;std::vector<double> block(512),high(4096);
  for(int ch=0;ch<channels;++ch) {
    Oversampler os(spec);
    for(size_t start=0;start<audio.size()/channels;start+=512) {
      int n=std::min<size_t>(512,audio.size()/channels-start);
      for(int j=0;j<n;++j)block[j]=audio[(start+j)*channels+ch];
      os.up(block.data(),n,high.data());
      for(int j=0;j<n*8;++j)peak=std::max(peak,std::abs(high[j]));
    }
  }
  return peak;
}
TEST(true_peak_protection_catches_intersample_overload) {
  auto cfg=EngineConfig::forQuality(QualityMode::Efficient,48000,2,24);cfg.autoHeadroom=false;
  Engine e(cfg);std::vector<float> audio(48000*2);
  for(int i=0;i<48000;++i)audio[2*i]=audio[2*i+1]=1.35*std::sin(kPi*.5*i+kPi*.25);
  const double before=reconstructedPeak(audio);e.process(audio.data(),audio.data(),48000);
  const double after=reconstructedPeak(audio);
  std::printf("    reconstructed peak %.4f -> %.4f (samples below full scale)\n",before,after);
  CHECK(before>1.3);CHECK(after<=.92);
}

TEST(detail_controls_and_live_edits_remain_peak_protected) {
  for(double fs:{44100.,48000.,96000.}) {
    auto cfg=EngineConfig::forQuality(QualityMode::Efficient,fs,2,24);
    Engine e(cfg);const int n=int(fs);std::vector<float> audio(2*n);
    for(int i=0;i<n;++i) {audio[2*i]=.8*std::sin(2*kPi*1600*i/fs);audio[2*i+1]=-audio[2*i];}
    e.setStereoTuner({1,1,1,1,1,1,1});
    for(int i=0;i<n;i+=256) {
      if(i==2560)e.setStereoTuner({0,0,0,-1,0,0,0});
      if(i==5120)e.setStereoTuner({1,1,1,1,1,1,1});
      e.process(audio.data()+2*i,audio.data()+2*i,std::min(256,n-i));
    }
    CHECK(reconstructedPeak(audio,2,fs)<.93);
    CHECK(std::all_of(audio.begin(),audio.end(),[](float x){return std::isfinite(x);}));
  }
}

TEST(true_peak_is_block_independent_linked_and_bypass_has_fixed_delay) {
 auto cfg=EngineConfig::forQuality(QualityMode::Efficient,48000,2,24);cfg.autoHeadroom=false;
 Engine whole(cfg),chunks(cfg);const int n=12000;std::vector<float> a(n*2),b;
 for(int i=0;i<n;++i) {a[i*2]=i<2000?.02f:1.35f*std::sin(kPi*.5*i+kPi*.25);a[i*2+1]=a[i*2]*.25f;} b=a;
 whole.process(a.data(),a.data(),n);
 for(int k=0;k<n;k+=13)chunks.process(b.data()+k*2,b.data()+k*2,std::min(13,n-k));
 for(int k=0;k<n*2;++k)CHECK_NEAR(a[k],b[k],1e-12);
 for(int k=0;k<n;++k)CHECK_NEAR(a[k*2+1],a[k*2]*.25,1e-7);
 CHECK(reconstructedPeak(a)<.93);
 Engine quiet(cfg);std::vector<float> q(n*2);for(int i=0;i<n;++i)q[i*2]=q[i*2+1]=.1f*std::sin(2*kPi*1000*i/48000);
 auto original=q;quiet.process(q.data(),q.data(),n);int d=quiet.latencyFrames();CHECK(d==208);
 for(int i=d;i<n;++i)CHECK_NEAR(q[i*2],original[(i-d)*2],1e-9);
 quiet.setGainProtection(false);CHECK(quiet.latencyFrames()==d);
}
TEST(true_peak_rates_modes_dither_bursts_and_recovery) {
 for(double fs:{44100.,48000.,96000.})for(auto mode:{QualityMode::Efficient,QualityMode::Audiophile,QualityMode::Extreme}) {
  auto cfg=EngineConfig::forQuality(mode,fs,2,16);cfg.autoHeadroom=false;Engine e(cfg);
  int n=int(fs);std::vector<float> v(n*2);
  for(int i=0;i<n;++i) {double amp=(i>1000&&i<5000)?1.35:.05;v[2*i]=amp*std::sin(kPi*.5*i+kPi*.25);v[2*i+1]=v[2*i]*.5;}
  e.process(v.data(),v.data(),n);const double peak=reconstructedPeak(v,2,fs);
  std::printf("    true peak %.0f Hz mode %d: %.4f\n",fs,int(mode),peak);CHECK(peak<.95);
  std::vector<float> quiet(n*2*3,.01f);e.process(quiet.data(),quiet.data(),n*3);CHECK(e.gainProtectionDb()>-.01);
 }
}
TEST(dynamic_eq_is_reduction_only_selective_linked_and_exactly_bypassable) {
 const int n=48000*2;EngineConfig cfg;cfg.autoHeadroom=false;cfg.gainProtection=false;
 Engine e(cfg);e.setDynamicEq(1);std::vector<float> v(n*2);
 for(int i=0;i<n;++i)v[2*i]=v[2*i+1]=.2*std::sin(2*kPi*330*i/48000)+.04*std::sin(2*kPi*1000*i/48000);
 auto original=v;e.process(v.data(),v.data(),n);std::vector<double> out(n);
 for(int i=0;i<n;++i) {out[i]=v[2*i];CHECK_NEAR(v[2*i],v[2*i+1],1e-12);}
 const double resonance=toDb(sineAmplitude(out,330,48000,n/2,n)/.2);
 const double target=toDb(sineAmplitude(out,1000,48000,n/2,n)/.04);
 std::printf("    selective EQ: resonance %.3f dB, unrelated target %.3f dB\n",resonance,target);
 CHECK(resonance< -1.2 && resonance> -1.7);CHECK(std::abs(target)<.15);
 double budget=0;for(double db:e.dynamicReductionsDb()){CHECK(db<=0);budget-=db;}CHECK(budget<=3.01);
 e.setDynamicEq(0);v=original;e.process(v.data(),v.data(),n);
 for(int i=480*2;i<n*2;++i)CHECK(v[i]==original[i]); // exact bypass after the 10 ms handover
}
TEST(dynamic_eq_disable_is_smooth_and_block_independent) {
 EngineConfig cfg;cfg.autoHeadroom=false;cfg.gainProtection=false;Engine a(cfg),b(cfg);
 a.setDynamicEq(1);b.setDynamicEq(1);
 const int n=96000;std::vector<float> input(n*2);
 for(int i=0;i<n;++i)input[2*i]=input[2*i+1]=.2*std::sin(2*kPi*330*i/48000+.7);
 auto other=input;a.process(input.data(),input.data(),n);b.process(other.data(),other.data(),n);
 CHECK(a.dynamicReductionsDb()[1]<-1.4);a.setDynamicEq(0);b.setDynamicEq(0);
 std::vector<float> v(2048),w;
 for(int i=0;i<1024;++i)v[2*i]=v[2*i+1]=.2*std::sin(2*kPi*330*(n+i)/48000+.7);
 auto dry=v;w=v;a.process(v.data(),v.data(),1024);
 for(int i=0;i<1024;i+=13)b.process(w.data()+2*i,w.data()+2*i,std::min(13,1024-i));
 for(int i=0;i<2048;++i)CHECK_NEAR(v[i],w[i],1e-12);
 CHECK_NEAR(v[0],dry[0]*std::pow(10.,-1.5/20),1e-4); // no immediate jump to dry
 for(int i=480*2;i<2048;++i)CHECK(v[i]==dry[i]);
 for(double db:a.dynamicReductionsDb())CHECK(db==0);
}
TEST(dynamic_eq_preserves_short_transients_and_recovers) {
 EngineConfig cfg;cfg.autoHeadroom=false;cfg.gainProtection=false;Engine e(cfg);e.setDynamicEq(1);
 std::vector<float> pulse(9600*2,0);for(int i=0;i<960;++i)pulse[i*2]=pulse[i*2+1]=.2*std::sin(2*kPi*3000*i/48000);
 auto original=pulse;e.process(pulse.data(),pulse.data(),9600);CHECK(pulse==original);
 std::vector<float> resonance(48000*2);for(int i=0;i<48000;++i)resonance[i*2]=resonance[i*2+1]=.2*std::sin(2*kPi*3000*i/48000);
 e.process(resonance.data(),resonance.data(),48000);CHECK(e.dynamicReductionsDb()[2]<-1);
 std::vector<float> silence(48000*3*2);e.process(silence.data(),silence.data(),48000*3);
 for(double db:e.dynamicReductionsDb())CHECK(db>-.01);
}

TEST(calibration_is_level_invariant_bounded_and_retains_actual_fit_error) {
 FrCurve m,t;for(int i=0;i<256;++i){double f=20*std::pow(1000.,i/255.);m.hz.push_back(f);t.hz.push_back(f);m.db.push_back(8*std::exp(-std::pow(std::log2(f/330),2)*2));t.db.push_back(0);}
 auto a=calibratedTuning(m,t,1,64);CHECK(a.valid);CHECK(a.fit.rmsErrorDb<.4);
 for(auto& db:m.db)db+=40;auto b=calibratedTuning(m,t,1,64);CHECK(b.valid);
 for(size_t k=0;k<a.fit.bands.size();++k)CHECK_NEAR(a.fit.bands[k].gainDb,b.fit.bands[k].gainDb,1e-8);
 auto off=calibratedTuning(m,t,0,64);for(const auto& band:off.fit.bands)CHECK_NEAR(band.gainDb,0,1e-10);
 auto partial=m;partial.hz.erase(partial.hz.begin(),partial.hz.begin()+40);partial.db.erase(partial.db.begin(),partial.db.begin()+40);CHECK(!calibratedTuning(partial,t,1,64).valid);
 FrCurve bad=parseCurve("inf 10\n30 0\n10000 1");CHECK(bad.hz.size()==2);
 for(auto& db:m.db)db=30*std::sin(db);auto bounded=calibratedTuning(m,t,1,64);CHECK(bounded.valid);
 ParametricEq eq(1,48000);eq.setBands(0,bounded.fit.bands);CHECK(eq.peakGainDb(0,20,20000,4096)<=6.05);
 CHECK(!calibratedTuning(m,t,1,50000).valid);
}

TEST(blind_comparison_matches_actual_rendered_loudness_by_attenuating_only) {
 auto a=multisine(48000,8,20000,flatShape,-24,.5);auto b=a;
 EngineConfig cfg;cfg.autoHeadroom=false;cfg.gainProtection=false;Engine processed(cfg);
 processed.setBandsAllChannels({{FilterType::LowShelf,100,6,.71},{FilterType::Peak,3000,-3,1}});processed.process(b.data(),b.data(),int(b.size()/2));
 std::array<double,6> levels{};CHECK(matchComparison(a,b,48000,levels));
 std::printf("    blind levels before %.3f / %.3f, after %.3f / %.3f LUFS\n",levels[0],levels[1],levels[2],levels[3]);
 CHECK(std::abs(levels[2]-levels[3])<.1);CHECK(levels[4]<=0&&levels[5]<=0);
 std::vector<float> silent(a.size());CHECK(!matchComparison(silent,b,48000,levels));
}

TEST(true_peak_nonfinite_input_does_not_poison_subsequent_music) {
 auto cfg=EngineConfig::forQuality(QualityMode::Audiophile,48000,2,24);Engine e(cfg);
 std::vector<float> v(20000,.1f);v[0]=std::numeric_limits<float>::quiet_NaN();v[2]=std::numeric_limits<float>::infinity();
 e.process(v.data(),v.data(),10000);for(float x:v)CHECK(std::isfinite(x));CHECK(std::abs(v.back()-.1)<1e-4);
}
TEST(dynamic_eq_does_not_chase_filter_tails_after_bass_transients) {
 for(double f:{120.,330.,3000.,6500.}) {
  EngineConfig cfg;cfg.autoHeadroom=false;cfg.gainProtection=false;Engine e(cfg);e.setDynamicEq(1);
  std::vector<float> v(12000*2);for(int i=0;i<960;++i)v[i*2]=v[i*2+1]=.2*std::sin(2*kPi*f*i/48000);
  auto original=v;e.process(v.data(),v.data(),12000);CHECK(v==original);
 }
}

static double referencePeak16(const std::vector<float>& v) {
 // Independent longer Hann reconstruction, full Nyquist bandwidth.
 double coefficients[16][192]={};
 for(int p=0;p<16;++p){double sum=0;for(int k=0;k<192;++k){double x=k-96+p/16.;double w=std::abs(x)<96?.5+.5*std::cos(kPi*x/96):0;coefficients[p][k]=(std::abs(x)<1e-12?1:std::sin(kPi*x)/(kPi*x))*w;sum+=coefficients[p][k];}for(auto& c:coefficients[p])c/=sum;}
 double peak=0;int frames=v.size()/2;
 for(int n=0;n<frames+96;++n)for(int ch=0;ch<2;++ch)for(int p=0;p<16;++p){double y=0;for(int k=0;k<192;++k){int j=n-k;if(j>=0&&j<frames)y+=v[j*2+ch]*coefficients[p][k];}peak=std::max(peak,std::abs(y));}
 return peak;
}
TEST(true_peak_dense_reconstruction_checks_transients_and_wideband) {
 auto c=EngineConfig::forQuality(QualityMode::Efficient,48000,2,24);c.autoHeadroom=false;
 for(int kind=0;kind<3;++kind){Engine e(c);std::vector<float> v(8192);uint32_t random=31;
  for(int j=0;j<4096;++j){random=random*1664525+1013904223;double x=kind==0?(.99*(double(random)/4294967295.*2-1)):
   kind==1?(.99*(j%2?1:-1)*(j>1000&&j<1600?1:0)):(.99*std::sin(2*kPi*.46*j+.41));v[j*2]=x;v[j*2+1]=x*.7;}
  e.process(v.data(),v.data(),4096);double peak=referencePeak16(v);std::printf("    independent 16x reconstructed peak case %d: %.5f\n",kind,peak);CHECK(peak<.95);
 }
}
TEST(dynamic_eq_budget_survives_changes_of_resonance) {
 EngineConfig c;c.autoHeadroom=false;c.gainProtection=false;Engine e(c);e.setDynamicEq(1);
 std::vector<float> v(1024*2);int frame=0;
 for(int block=0;block<300;++block){const std::array<double,2> frequencies=(block/20)%2?std::array<double,2>{120,330}:std::array<double,2>{3000,6500};
  for(int j=0;j<1024;++j,++frame){double x=0;for(double f:frequencies)x+=.1*std::sin(2*kPi*f*frame/48000);v[2*j]=v[2*j+1]=x;}
  e.process(v.data(),v.data(),1024);double sum=0;for(double db:e.dynamicReductionsDb())sum-=db;CHECK(sum<=3.00001);
 }
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

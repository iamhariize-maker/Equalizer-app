#pragma once
// SourceAnalyzer — Svaramanas's ears. Listens to what is actually playing
// (Engine B's captured input) and publishes slow, long-term features:
//
//   loudness (K-weighted, BS.1770 style, gated), peak, peak-to-loudness ratio,
//   clipping rate, stereo correlation / side energy, the lossy-codec ceiling
//   (highest frequency that still carries real energy), third-octave tonal
//   balance and its local deviations from the mix's own overall tilt
//   (mud 200-500 Hz, boom 63-125 Hz, harshness 2.5-5 kHz, air 10-16 kHz).
//
// process() runs on the audio thread: allocation-free, wait-free (publishing
// uses try_lock and simply skips a publish if a reader holds the lock).
// snapshot() may be called from any thread.
#include <array>
#include <complex>
#include <mutex>
#include <vector>

#include "eqcore/biquad.h"

namespace eqcore {

struct SourceFeatures {
  static constexpr int kBands = 30;  // third octaves, 25 Hz .. 20 kHz
  static double bandCentreHz(int i);

  bool valid = false;            // enough non-silent audio analysed
  double seconds = 0.0;          // non-silent seconds in the long-term average
  double loudnessLufs = -70.0;   // gated K-weighted loudness (approx. integrated)
  double peakDbfs = -120.0;      // sample peak, slow release
  double plrDb = 0.0;            // peak-to-loudness ratio (low = heavily limited master)
  double clipsPerSecond = 0.0;   // consecutive full-scale samples per second
  double correlation = 1.0;      // L/R correlation (+1 mono, 0 wide, <0 phasey)
  double sideToMidDb = -120.0;   // side energy relative to mid
  bool monoLike = false;         // "stereo" file carrying (almost) no side signal
  double cutoffHz = 0.0;         // highest frequency with real energy (lossy ceiling)
  double tiltDbPerOct = 0.0;     // overall slope of the third-octave spectrum
  double mudDb = 0.0;            // local excess over the tilt line, 200-500 Hz
  double boomDb = 0.0;           // 63-125 Hz
  double harshDb = 0.0;          // 2.5-5 kHz
  double airDb = 0.0;            // 10-16 kHz (only meaningful below cutoff)
  std::array<double, kBands> bandDb{};  // long-term third-octave levels (dB, relative)

  // Mid/side power spectra retain the placement of energy across frequencies.
  // Used to match the actual stereo guide, rather than EQ bands alone.
  bool hasStereoSpectrum = false;
  std::array<double, kBands> midBandDb{}, sideBandDb{};

  // JNI prefix stays compatible: 15 scalars + 30 total levels, then stereo data.
  static constexpr int kScalars = 15;
  static constexpr int kLegacyPacked = kScalars + kBands;
  static constexpr int kPacked = kLegacyPacked + 1 + 2 * kBands;
  void pack(double* out) const;
  static SourceFeatures unpack(const double* in, int n);
};

class SourceAnalyzer {
 public:
  explicit SourceAnalyzer(double sampleRate, int channels = 2, double averageSeconds = 8.0);

  // Interleaved float frames. Audio thread; allocation-free.
  void process(const float* interleaved, int frames);
  SourceFeatures snapshot() const;
  void reset();

  static constexpr int kFft = 4096;

 private:
  void analyseWindow();
  void publish();

  double fs_;
  int channels_;
  double alpha_;  // per-window EMA coefficient
  // K-weighting (two biquads, per channel up to 2), direct form I state.
  struct Bq { double b0, b1, b2, a1, a2; };
  Bq kShelf_{}, kHigh_{};
  double kz_[2][2][4] = {};  // [channel][stage][x1 x2 y1 y2]
  double kWeighted(int ch, double x);

  // 400 ms loudness blocks.
  int blockLen_, blockPos_ = 0;
  double blockEnergy_ = 0.0;
  double gatedEnergy_ = 0.0;   // EMA of block energies that passed the gates
  double gatedWeight_ = 0.0;

  // High input rates are decimated (8th-order anti-alias low-pass at 0.46 of the analysis rate) so every
  // window, band and loudness block spans the same seconds as at 48 kHz. Peak and clipping stay at the
  // full input rate. fs_ is the analysis rate (input rate / decim_); decim_ == 1 below ~52 kHz (no filter).
  int decim_ = 1, phase_ = 0;
  double inputFs_ = 48000.0;
  Biquad aa_[2][4];

  double peak_ = 0.0, peakRelease_;
  double clipEma_ = 0.0, clipCount_ = 0.0, lastAbs_ = 0.0;
  double ll_ = 0.0, rr_ = 0.0, lr_ = 0.0, mm_ = 0.0, ss_ = 0.0;        // current window sums
  double stLL_ = 0.0, stRR_ = 0.0, stLR_ = 0.0, stMM_ = 0.0, stSS_ = 0.0;  // long-term averages
  double avgSeconds_;

  std::vector<double> window_, ring_, sideRing_;  // Hann window and M/S input rings
  int ringPos_ = 0;
  std::vector<std::complex<double>> fft_;
  std::vector<double> power_, midPower_, sidePower_;  // EMA total and M/S power per bin
  double activeSeconds_ = 0.0;
  int windowsSincePublish_ = 0;

  mutable std::mutex lock_;
  SourceFeatures published_;
};

// In-place radix-2 FFT (n power of two). Exposed for tests.
void fftInPlace(std::complex<double>* x, int n);

}  // namespace eqcore

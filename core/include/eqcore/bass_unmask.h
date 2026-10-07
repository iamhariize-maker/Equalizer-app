#pragma once
// Selective bass unmasking (AQ-03 B). Attenuation only, stereo-linked, default off.
//
// Idea: a bass NOTE is a fundamental plus harmonic partials. A sustained spectral peak in the low
// range that does not belong to that note's harmonic series and sits well above the note's own
// level can mask it. Cut that peak's lane by a little. Nothing else is ever touched.
//
// Evidence chain (every link must hold, otherwise the cut target is 0 -- "unknown means skip"):
//   1. A decimated (~2 kHz) 128 ms Hann spectrum, zero-padded to ~2 Hz bins, with signal above -66 dBFS.
//   2. A validated note: a low peak (35-200 Hz) with >= 2 partials among 2f..5f, each within 3.5 % (or 4 Hz)
//      and no more than 30 dB below it, stable (within 3 %) for 3 consecutive 32 ms frames. A lone tone
//      (kick, pure sub-bass, sine test tone) has no partials: not a note, no verdict, no cut.
//   3. Per lane (70/110/180/280 Hz, +-25 % band): the strongest peak is not within 4 % (or 5 Hz) of any of the
//      note's harmonics 1..8 and not below 0.9 f0 (a possible sub-harmonic), and exceeds the note's level
//      by more than 6 dB for at least 5 consecutive frames (~150 ms of evidence beyond the window).
//   4. Cut = amount * confidence * clamp(0.5 * (excess - 6), 0, 1.5) dB per lane, combined <= 2 dB.
//      Confidence 0.75..1 grows with the number of partials found.
// Smoothing 50 ms attack / 400 ms release; an onset (fast low-band envelope 6 dB over a slow one) releases
// existing cuts in ~30 ms and blocks new ones for 100 ms. It is causal: the first ~ms of an onset arriving
// during a cut can still be down by that cut (never more than 2 dB), which the tests measure.
//
// This is NOT instrument recognition and does not know whether a peak is "wrong" musically: an inharmonic
// partial that is part of a bell-like or detuned sound would be cut too. Hence the explicit amount (default 0).
#include <array>
#include <atomic>
#include <complex>
#include <vector>

#include "eqcore/biquad.h"

namespace eqcore {

class BassUnmask {
 public:
  static constexpr int kLanes = 4;
  explicit BassUnmask(double sampleRate);

  // Any thread, 0..1; 0 = off. Non-finite counts as 0.
  void setAmount(double amount);

  // In place, left/right of one block (right may be null for mono). Allocation-free. Exact bypass at zero.
  void process(double* left, double* right, int frames);
  void reset();

  // Diagnostics are published atomically at the end of every process() call, so any thread may read them.
  std::array<double, kLanes> cutsDb() const {                         // <= 0
    return {pubCut_[0].load(std::memory_order_relaxed), pubCut_[1].load(std::memory_order_relaxed),
            pubCut_[2].load(std::memory_order_relaxed), pubCut_[3].load(std::memory_order_relaxed)};
  }
  bool cutting() const { return cutting_.load(std::memory_order_relaxed); }  // any lane cut > 0.05 dB
  double noteHz() const { return pubNote_.load(std::memory_order_relaxed); }  // 0 when unknown
  static constexpr std::array<double, kLanes> laneHz() { return {70, 110, 180, 280}; }

 private:
  using Cx = std::complex<double>;
  struct Peak { double hz, db; };
  void frame();
  void fft(Cx* x) const;
  void applyGains();

  double fs_;
  int decim_, ring_ = 256, fftN_ = 1024;
  double fsd_;
  std::atomic<double> amount_{0};
  double amountApplied_ = 0;
  Biquad lp_[4];
  int phase_ = 0, sinceFrame_ = 0, ringPos_ = 0;
  std::vector<double> decimated_, window_;
  std::vector<Cx> buf_, twiddle_;
  std::vector<int> bitrev_;
  std::vector<double> mag_;
  bool noteValid_ = false;
  double noteHz_ = 0, noteDb_ = -200, confidence_ = 0;
  int stableFrames_ = 0;
  std::array<int, kLanes> qualified_{};
  std::array<double, kLanes> targetDb_{}, cutDb_{}, excess_{};
  std::array<Biquad, kLanes> filter_[2];
  std::array<double, kLanes> designedDb_{};
  double aAttack_, aRelease_, aFastRelease_, envFast_ = 0, envSlow_ = 0, aFastUp_, aFastDn_, aSlowUp_, aSlowDn_;
  int onsetBlock_ = 0, onsetBlockFrames_, sampleCounter_ = 0;
  bool onsetActive_ = false;
  std::atomic<bool> cutting_{false};
  std::atomic<double> pubCut_[kLanes] = {{0}, {0}, {0}, {0}};
  std::atomic<double> pubNote_{0};
  void publish();
};

}  // namespace eqcore

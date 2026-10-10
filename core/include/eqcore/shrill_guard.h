#pragma once
// Sustained-shrill guard: reduction-only, stereo-linked, bounded, transient-friendly.
//
// Two bands are acted on, each a fourth-order band-pass with zero phase at its centre: presence at 4 kHz (guitar
// and vocal shrillness, where the ear is most sensitive to sharpness) and sizzle at 8 kHz (cymbal and hi-hat).
// Whether a band is shrill is judged on the track, not on absolute band levels (those were inert on music: a
// music-like spectrum sits 11 dB under any fixed threshold that leaves pink noise alone). The source analyser
// supplies the band's residual against the mix's own least-squares tilt (setExcess): presence from 2.5-5 kHz,
// sizzle from 6-10 kHz. Pink and -6 dB/octave material read about 0 dB; a shrill master reads several dB. Above
// 1.5 dB (presence) or 2.0 dB (sizzle) the band is reduced by 0.5 dB per dB of excess, capped at 2 dB per band at
// full depth. Without a valid residual (analysis off, or under 3 s of audio heard) the guard does nothing.
//
// Voice protection: a close-miked voice has a natural 3-5 kHz presence peak (a +9 dB "singer's formant" reads about
// +3 dB of residual) that must not be shaved. Shrill guitars and cymbals are usually spread across the stereo field;
// a lead voice sits in the centre. When the centre leads the sides over 1-4 kHz by more than 10 dB, the presence
// reduction is scaled down, to a quarter at 20 dB and above. Sizzle is not scaled. A mono shrill source therefore
// gets a quarter of the presence reduction: the price of protecting voices, to be calibrated on real excerpts.
//
// Sustain gate: while a band's fast (2 ms) power runs well above its slow (40 ms) power, the sound is an attack (a
// hi-hat click, a pick, a consonant). The gate closes, so the attack passes unchanged; once the energy holds, the
// gate opens and the ringing that makes a sound shrill is reduced.
//
// The cut is a parallel band subtraction, x + (gain - 1) * band(x), exact at each band's centre. Its skirts carry
// small phase effects, and where two bands overlap the sum is bounded by the two caps (at most 4 dB). Both channels
// receive the same band gain, so the stereo image cannot move. depth 0 is a bit-exact bypass. Thresholds and caps
// are design values, not listening results: docs/SOUND_RESEARCH_0.5.13.md states what is measured and what still
// needs blind A/B tests. No look-ahead, so no latency. Allocation-free and wait-free on the audio thread.
#include <array>
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

class ShrillGuard {
 public:
  static constexpr int kBands = 2;
  explicit ShrillGuard(double sampleRate);

  // Any thread; picked up at the next block. Clamped to 0..1; non-finite counts as 0.
  void setDepth(double depth);

  // Any thread: the analyser's presence (2.5-5 kHz) and sizzle (6-10 kHz) residuals, dB over the mix's tilt.
  // Non-finite means unknown: that band is left alone.
  void setExcess(double presenceDb, double sizzleDb, double centreDb = 0.0);

  // Any thread: the most the presence band may take, dB (0..2). The engine lowers it when other stages already reduce
  // 3-6 kHz, so the reductions together stay inside one budget.
  void setPresenceCap(double db);
  double depth() const { return depth_.load(std::memory_order_relaxed); }

  void reset();

  // In place. right may be null (mono). Allocation-free.
  void process(double* left, double* right, int frames);

  // Last reduction applied per band, dB (<= 0), for UI and tests.
  std::array<double, kBands> reductionsDb() const;

 private:
  struct Bq {
    BiquadCoeffs c;
    double z1 = 0, z2 = 0;
    double run(double x) {
      const double y = c.b0 * x + z1;
      z1 = c.b1 * x - c.a1 * y + z2;
      z2 = c.b2 * x - c.a2 * y;
      return y;
    }
  };
  struct Band {
    Bq passL[2], passR[2];  // two cascaded second-order sections: fourth order
    double medium = 0.0, slow = 0.0, gain = 1.0;  // power envelopes (2 ms, 40 ms) and the applied linear gain
  };

  std::atomic<double> depth_{0.0};
  std::array<std::atomic<double>, kBands> excess_{};
  std::atomic<double> presenceScale_{1.0};
  std::atomic<double> presenceCap_{2.0};
  std::array<Band, kBands> bands_{};
  double aMedium_ = 0.0, aSlow_ = 0.0, aGain_ = 0.0;
  std::array<std::atomic<double>, kBands> reductionDb_{};
};

}  // namespace eqcore

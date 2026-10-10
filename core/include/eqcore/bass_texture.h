#pragma once
// Bass texture and detail: level-gated harmonics for the bass notes, plus optional attack definition, dimension and
// sustain, so the bass has weight, shape and a sense of size on systems that cannot reproduce 40-80 Hz (phones,
// small speakers, earbuds) and detail on systems that can.
//
// Why: a bass note is heard through its harmonics as well as its fundamental (the missing-fundamental effect).
// Adding a few harmonics makes the line audible without adding sub-bass energy. Its pick or slap, its decay and
// the size of the note are separate layers in time; each gets its own bounded handle.
//
// Texture (depth, mid channel only):
//   sub   = 4th-order low-pass at 150 Hz of the mid signal
//   shape = tanh(k * sub) / k, an odd-order shaper with small-signal gain 1; drive k rises with the
//           note's level (0.5 quiet .. 4.5 at -12 dBFS), so quiet notes stay linear and loud ones gain weight
//   even  = evenMix * g(level) * |sub|, the full-wave rectified sub band: second (and fourth) harmonics, the
//           "tube" colour; default 0
//   raw   = shape - N(level) * sub + even
//   wet   = high-pass 30 Hz of (raw - p * sub), p = <raw * sub> / <sub^2> over 50 ms
// N(level) is the shaper's own fundamental gain for that level (a precomputed describing function), and the
// projection p removes what remains in phase with the bass band (a note's own partials intermodulate onto its
// fundamental), so the notes keep their level: wet carries only new harmonics. The 30 Hz high-pass removes the
// rectifier's DC. Output adds depth * wet equally to L and R.
//
// Dimension (spread, needs texture): only the texture's harmonics above about 200 Hz get a small fixed phase
// difference between left and right (a first-order all-pass at 1.5 kHz, about 0.2 ms at low frequencies), added as a
// pure side signal: the mono sum, the fundamental and everything below 150 Hz stay exactly where they were.
//
// Attack (attack): an onset on the sub band (its 1 ms power running well above its 50 ms power) opens a short
// window (1 ms rise, 10 ms fall) that lifts the 0.6-2.5 kHz pick/slap band of the mid by up to 2 dB. Between onsets
// the band is not touched.
//
// Sustain (sustain): while a bass note decays below its own recent peak, the sub band is lifted by
// min(sqrt(peak / level), 3 dB): the tail of a held or ringing note keeps its shape, never rises above the note's
// peak, and an attack (level at its peak) is never lifted. Ignored under about -60 dBFS so rumble and noise stay put.
//
// Properties (all tested in core/tests/test_main.cpp):
//   - every control at 0 is a bit-exact bypass;
//   - the texture alone never changes the side channel or content above ~1 kHz;
//   - at -12 dBFS, full depth puts the 3rd harmonic about -25 dBc; evenMix 1 adds a 2nd harmonic about -23 dBc;
//   - nothing tracks pitch, so gliding notes (808s, tabla and dholak bass heads) keep their glide.
// No look-ahead, so no latency. Allocation-free and wait-free on the audio thread.
#include <array>
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

class BassTexture {
 public:
  explicit BassTexture(double sampleRate);

  // Any thread; picked up at the next block (20 ms ramp). Each is clamped to 0..1; non-finite counts as 0.
  void setDepth(double depth);
  void setEvenMix(double evenMix);
  void setAttack(double attack);
  void setSpread(double spread);
  void setSustain(double sustain);
  double depth() const { return target_[kDepth].load(std::memory_order_relaxed); }
  // What the two time-varying controls did in the last block, dB (>= 0), for the Lab readouts. Zero when the control
  // is off or idle. Any thread; relaxed. Attack is the pick/slap lift (at most 2 dB), sustain the decay lift (at most 3 dB).
  double attackLiftDb() const { return attackLiftDb_.load(std::memory_order_relaxed); }
  double sustainLiftDb() const { return sustainLiftDb_.load(std::memory_order_relaxed); }

  void reset();

  // In place. right may be null (mono; no dimension). Allocation-free.
  void process(double* left, double* right, int frames);

  // Fundamental describing-function gain of the shaper at amplitude `level` (1 at zero level). Exposed for tests.
  static double fundamentalGain(double level);

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
  enum Control { kDepth, kEven, kAttack, kSpread, kSustain, kControls };
  static constexpr int kTableSize = 129;  // levels 0..kTableMax in equal steps
  static constexpr double kTableMax = 2.0;
  static const std::array<double, kTableSize>& table();
  void setControl(Control c, double v);

  std::array<std::atomic<double>, kControls> target_{};
  std::atomic<double> attackLiftDb_{0.0};
  std::atomic<double> sustainLiftDb_{0.0};
  std::array<double, kControls> value_{};
  double aRamp_ = 0.0, aUp_ = 0.0, aDown_ = 0.0;
  double level_ = 0.0;
  Bq lowLp_[2], highPass_[2];
  // In-phase projection: generated signal correlated with the bass band is removed (keeps notes at their level).
  double aProject_ = 0.0, corr_ = 0.0, subPower_ = 0.0;
  // Dimension: high-pass 200 Hz of the wet harmonics, then a first-order all-pass.
  Bq spreadHp_;
  double apCoeff_ = 0.0, apX1_ = 0.0, apY1_ = 0.0;
  // Attack: onset detector on the sub band and the pick-band filter on the mid.
  double aOnsetFast_ = 0.0, aOnsetSlow_ = 0.0, aGateUp_ = 0.0, aGateDown_ = 0.0;
  double onsetFast_ = 0.0, onsetSlow_ = 0.0, gate_ = 0.0;
  Bq pickBand_;
  // Sustain: the note's level and its recent peak.
  double aEnvUp_ = 0.0, aEnvDown_ = 0.0, aPeakDown_ = 0.0, aLiftUp_ = 0.0;
  double env_ = 0.0, peak_ = 0.0, lift_ = 1.0;
};

}  // namespace eqcore

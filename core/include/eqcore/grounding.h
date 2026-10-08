#pragma once
// Grounding — Svan's "organic body" stage (docs/SONIC_IDENTITY.md).
//
// Some masters (and most high-resolution renders of them) sound airy and
// spiky: the top-end transients stand taller than the body of the music, so
// the voice and the driving rhythm lose their weight. Grounding rebalances
// that without flattening anything. Two independent, bounded parts:
//
//   restraint  A stereo-linked, downward-only transient restrainer on the band
//              above ~3.5 kHz. It reacts to the *crest* of that band (fast
//              level against its own slow level) and only to the part of a
//              spike beyond a soft threshold. Sustained air is untouched,
//              typical transients pass unchanged, and the largest reduction is
//              4 dB at full depth (soft ratio, 0.5 ms attack, 15 ms release).
//   body       Level-dependent SYMMETRIC (odd-order) saturation of the
//              100 Hz - 1 kHz band (voice fundamentals, guitar and bass bodies,
//              snare shell). Harmonics grow with level (3rd rises ~40 dB per
//              decade), so quiet passages stay clean and loud ones gain weight.
//              At a -12 dBFS band level, full depth measures about -26 dB
//              3rd-harmonic content and 1.1 dB of fundamental compression; at
//              -20 dBFS about -42 dB. Tanh-capped, with a level knee (see
//              kBodyKnee) that backs the saturation off on loud peaks so it
//              adds weight but never grit. Even-order harmonics are
//              OFF by default: the one controlled study found asymmetric
//              distortion the less pleasant kind (docs/RESEARCH_GROUNDED_SOUND.md
//              section 3). `evenMix` exists only so blind tests can A/B it.
//
// Both are parallel add-ins (y = x + wet), so depth 0 is bit-exact bypass,
// and depth changes ramp over 20 ms. process() is allocation-free and
// wait-free. No look-ahead, so no added latency. Runs at the base sample rate
// (the band is far below Nyquist, so harmonic aliasing is negligible).
#include <atomic>

#include "eqcore/biquad.h"

namespace eqcore {

struct GroundingParams {
  double restraint = 0.0;  // 0..1 depth of the HF transient restrainer
  double body = 0.0;       // 0..1 depth of the low-mid harmonic body
  double evenMix = 0.0;    // 0..1 experimental even-order share (default off; blind-test knob)
  bool isOff() const { return restraint == 0.0 && body == 0.0; }
};

class Grounding {
 public:
  explicit Grounding(double sampleRate);

  // Any thread; picked up at the next block. Values are clamped to 0..1.
  void setParams(const GroundingParams& p);

  // In-place. right may be null (mono). Allocation-free.
  void process(double* left, double* right, int frames);
  void reset();

  // Largest reduction currently applied to the top band, dB (<= 0), for UI/tests.
  double restraintDb() const { return restraintDb_.load(std::memory_order_relaxed); }

  static constexpr double kMaxRestraintDb = 4.0;
  static constexpr double kHfCornerHz = 3500.0;

 private:
  struct Chan {
    Biquad hf[2];            // 4th-order Butterworth low-pass; the top band is x minus this
    Biquad bodyHp, bodyLp;   // 100 Hz .. 1 kHz band
    double evenDc = 0.0;     // slow mean of band^2 (removed from the even term)
  };
  double fs_;
  std::atomic<double> restraintTarget_{0}, bodyTarget_{0}, evenTarget_{0};
  double restraintMix_ = 0, bodyMix_ = 0, evenMix_ = 0, mixStep_;
  Chan ch_[2];
  double fast_ = 0, slow_ = 0, gain_ = 1;
  double aFast_, aSlow_, aAttack_, aRelease_, aDc_, aEnv_;
  double bodyEnv_ = 0;
  std::atomic<double> restraintDb_{0};
};

}  // namespace eqcore

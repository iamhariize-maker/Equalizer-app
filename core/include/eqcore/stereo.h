#pragma once
// Vocal tuner + instrument amplifier, using mid/side processing.
//
// Lead vocals and bass often sit near the centre (mid = L+R), while
// instruments and ambience may spread to the sides (side = L-R). M/S filters
// target these parts of the mix separately; they do not isolate instruments
// or vocals. Shared content is affected too. No artificial reverb or delay.
//
// Vocal tuner (mid only):
//   intimacy   0..1  voice body & formants forward
//   warmth     0..1  chest warmth (220 Hz) + softened top end
//   smoothness 0..1  dynamic de-harsher: cuts the 2.5-6 kHz "shrill" band only
//                     while it spikes relative to the rest of the voice
// Instrument amplifier (side only, above ~180 Hz so bass stays centred):
//   space      -1..1 caved in .. spacious (side level, +-6 dB)
//   instruments 0..1 string/sax presence, body and air on the sides
//   backingVocals 0..1 broad side vocal-region bell (1600 Hz, up to 2.5 dB)
//   spatialDetail 0..1 side high shelf (4000 Hz, up to 2 dB); no synthesized cues
#include <atomic>
#include <array>

#include "eqcore/biquad.h"

namespace eqcore {

struct StereoTunerParams {
  double intimacy = 0, warmth = 0, smoothness = 0;
  double space = 0, instruments = 0;
  double backingVocals = 0, spatialDetail = 0; // 0..1, existing side detail only
  bool operator==(const StereoTunerParams& b) const {
    return intimacy==b.intimacy && warmth==b.warmth && smoothness==b.smoothness &&
        space==b.space && instruments==b.instruments && backingVocals==b.backingVocals && spatialDetail==b.spatialDetail;
  }
  bool isOff() const {
    return intimacy == 0 && warmth == 0 && smoothness == 0 && space == 0 && instruments == 0 && backingVocals == 0 && spatialDetail == 0;
  }
};

// Power response of the static M/S filters, including the complex LR4 sum.
// Dynamic de-harshing is signal-dependent and is not modelled here.
std::array<double, 2> stereoResponsePower(const StereoTunerParams& p, double freqHz, double sampleRate);

class StereoTuner {
 public:
  explicit StereoTuner(double sampleRate);

  // Any thread; live changes crossfade for 20 ms. Updates during a fade coalesce.
  void setParams(const StereoTunerParams& p);

  // In-place on one block of left/right samples. Allocation-free.
  void process(double* left, double* right, int frames);
  void reset();

  // Last de-harsh gain reduction applied, dB (<= 0). For UI meters/tests.
  double lastDeharshDb() const { return lastDeharshDb_; }

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
  struct State {
    StereoTunerParams p_;
    Bq warmBell_, warmShelf_, intimacyBell_, harshBand_;
    Bq sideLp_[2], sideHp_[2], bodyBell_, presenceBell_, airShelf_, backingBell_, detailShelf_;
    double envBand_ = 1e-9, envFull_ = 1e-9, deharshGain_ = 1.0, spaceGain_ = 1.0;
    double aBand_, rBand_, aFull_, rFull_, gSmooth_;
    void redesign(const StereoTunerParams& p, double fs);
    void reset();
    double process(double& left, double& right);
  };
  double fs_;
  int fadeFrames_, fadeRemaining_ = 0, active_ = 0;
  bool initialized_ = false;
  std::array<State,2> states_;
  std::atomic<int> version_{0};
  int appliedVersion_ = -1;
  StereoTunerParams pending_;
  std::atomic<bool> pendingLock_{false};
  double lastDeharshDb_ = 0;
};

}  // namespace eqcore

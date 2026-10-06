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
//   backingVocals 0..1 vocal-layer de-masker. A static side bell (1600 Hz, up
//                     to 2 dB) plus a dynamic side vocal-band lift (up to 4 dB)
//                     that rises only while the side vocal band is masked by a
//                     much louder centre (lead) vocal band, and backs off when
//                     the layers are already prominent.
//   spatialDetail 0..1 "Binaural" in the UI. Mono-safe image motion enhancer:
//                     Blumlein-style side spaciousness bell (500 Hz, up to
//                     2.5 dB), side air shelf (4 kHz, up to 1.5 dB), and a
//                     dynamic per-band (<1k / 1-4k / >4k) side lift of up to
//                     ~5 dB driven by how fast each band's left/right position
//                     is moving. Static images stay put; channel-to-channel
//                     movement already in the recording is exaggerated.
// Every side-channel control leaves L+R (the mono sum) unchanged: nothing is
// synthesized, delayed or reverberated.
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
    Bq sideLp_[2], sideHp_[2], bodyBell_, presenceBell_, airShelf_, backingBell_, detailShelf_, shuffleBell_;
    double envBand_ = 1e-9, envFull_ = 1e-9, deharshGain_ = 1.0, spaceGain_ = 1.0;
    double aBand_, rBand_, aFull_, rFull_, gSmooth_;
    // Backing-vocal de-masker: matched vocal-band filters on side and mid.
    Bq vocalSide_, vocalMid_;
    double envVocalSide_ = 0, envVocalMid_ = 0, backingLiftDb_ = 0;
    double aVocal_, rVocal_, aLift_, rLift_;
    // Image-motion enhancer: complementary 3-band split (exact sum) of side and
    // of a 180 Hz high-passed mid used for detection only.
    static constexpr int kBands = 3;
    Bq motionHp_, splitSide_[2], splitMid_[2];
    double cross_[kBands] = {}, power_[kBands] = {}, panSlow_[kBands] = {}, motionGain_[kBands] = {1, 1, 1};
    bool heard_[kBands] = {};
    double aPan_, aSlow_, aMotionUp_, aMotionDown_;
    double backingLift(double highSide, double mid);
    double motion(double highSide, double mid);
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

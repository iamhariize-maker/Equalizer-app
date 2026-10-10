#pragma once
// The full processing chain used by both the capture engine (Android) and
// any future player mode:
//
//   float in -> 64-bit -> preamp (+ auto headroom) -> [oversample up]
//            -> parametric EQ at the high rate -> [oversample down]
//            -> bass character (punch/sustain) -> vocal tuner / instrument amp (M/S)
//            -> grounding (HF transient restraint + low-mid body)
//            -> gain protection
//            -> dither to the output word length -> float out
//
// QualityMode::Audiophile spends CPU on precision: EQ runs at 4x (or 8x) the
// sample rate so high-frequency bands keep their analog shape instead of
// being "cramped" near Nyquist, and the output is TPDF-dithered.
#include <atomic>
#include <cmath>
#include <memory>
#include <vector>

#include "eqcore/analyzer.h"
#include "eqcore/true_peak.h"
#include "eqcore/dynamic_eq.h"
#include "eqcore/grounding.h"
#include "eqcore/bass_texture.h"
#include "eqcore/shrill_guard.h"
#include "eqcore/analog_top.h"
#include "eqcore/lab_eq.h"
#include "eqcore/bass.h"
#include "eqcore/bass_unmask.h"
#include "eqcore/dither.h"
#include "eqcore/oversampler.h"
#include "eqcore/parametric_eq.h"
#include "eqcore/stereo.h"

namespace eqcore {

enum class QualityMode {
  Efficient,   // 1x, no dither
  HighQuality, // 2x oversampling, TPDF dither
  Audiophile,  // 4x oversampling, 120 dB FIRs, TPDF dither
  Extreme,     // 8x oversampling, 140 dB FIRs, noise-shaped TPDF dither
};

struct EngineConfig {
  double sampleRate = 48000.0;
  int channels = 2;
  int oversample = 1;        // 1, 2, 4, 8
  double stopbandDb = 120.0; // oversampler FIR attenuation
  int ditherBits = 0;        // 0 = off; 16 or 24 typical
  DitherMode ditherMode = DitherMode::Tpdf;
  bool autoHeadroom = true;  // pre-attenuate by the curve's max boost (predictive)
  bool gainProtection = true; // Automatic Gain Protection (reactive):
                              // on overload lower gain; recover smoothly with 250 ms release
  bool truePeak = false;     // enabled by quality presets and Android capture
  int maxBlock = 1024;       // frames per internal chunk
  bool spatialResidual = false; // "Detailed" Backing vocals/Binaural (streaming WOLA, adds latency; stereo only)
  LabEqConfig lab; // opt-in static EQ replacement; immutable for this capture epoch

  static EngineConfig forQuality(QualityMode mode, double sampleRate, int channels, int outputBits);
};

class Engine {
 public:
  explicit Engine(const EngineConfig& cfg);

  const EngineConfig& config() const { return cfg_; }

  // Thread-safe; call from the UI thread.
  void setBands(int channel, const std::vector<BandParams>& bands);
  void setBandsAllChannels(const std::vector<BandParams>& bands);
  void setPreampDb(double db);
  void setAutoHeadroom(bool enabled) { if(autoHeadroom_.exchange(enabled)!=enabled) updateGain(); }
  void setGainProtection(bool enabled) { gainProtection_.store(enabled); }
  // Bass character: -1 sustain .. 0 off .. +1 punch; crossover 60..250 Hz.
  // Thread-safe: applied by the audio thread at the next block.
  void setDynamicEq(double amount) { dynamicAmount_.store(amount); }
  std::array<double,4> dynamicReductionsDb() const {return {dynamicDb_[0].load(),dynamicDb_[1].load(),dynamicDb_[2].load(),dynamicDb_[3].load()};}
  void setBassCharacter(double character, double crossoverHz = 120.0);
  // Bass Resolve 0..1 (see BassShaper::setResolve). Thread-safe; applied at the next block.
  // Selective bass unmasking 0..1 (see BassUnmask). Default 0 = off and bit-exact; stereo-linked, reduction only.
  void setBassUnmask(double amount) { unmask_.setAmount(amount); }
  std::array<double, 4> bassUnmaskCutsDb() const { return unmask_.cutsDb(); }
  double bassUnmaskNoteHz() const { return unmask_.noteHz(); }  // 0 = no validated note
  void setBassResolve(double resolve) { bassResolve_.store(std::isfinite(resolve) ? resolve : 0.0); }
  // Vocal tuner + instrument amplifier (stereo engines only; mono ignores it).
  void setStereoTuner(const StereoTunerParams& p) { stereo_.setParams(p); }
  void setSpatialMode(int mode) { stereo_.setSpatialMode(mode); }
  void setSpatialLoadLimited(bool on) { stereo_.setSpatialLoadLimited(on); }
  double detailedMix() const { return stereo_.detailedMix(); }
  // Grounding: HF transient restraint + low-mid harmonic body (mono and stereo).
  void setGrounding(const GroundingParams& p) { grounding_.setParams(p); }
  double groundingRestraintDb() const { return grounding_.restraintDb(); }
  // Bass texture 0..1: level-gated odd harmonics for bass notes (see BassTexture). Default 0 = off, bit-exact.
  void setBassTexture(double depth) { texture_.setDepth(depth); }
  // Bass detail, each 0..1, default 0 (bit-exact): even ("tube") harmonics, the pick/slap attack lift, the side-only
  // dimension of the harmonics, and the sustain of decaying notes (see BassTexture).
  void setBassEvenMix(double v) { texture_.setEvenMix(v); }
  void setBassAttack(double v) { texture_.setAttack(v); }
  void setBassSpread(double v) { texture_.setSpread(v); }
  void setBassSustain(double v) { texture_.setSustain(v); }
  // Sustained-shrill guard 0..1: reduces sustained 4 kHz presence and 8 kHz sizzle when the analyser finds them in
  // excess over the track's own tilt; attacks pass. Needs setAnalysisEnabled(true) (see ShrillGuard).
  // Default 0 = off, bit-exact.
  void setShrillGuard(double depth) { shrill_.setDepth(depth); }
  // Analogue top 0..1: loud, sustained 6-12 kHz energy softens by up to 2.5 dB; quiet air and clicks pass (see AnalogTop).
  // Default 0 = off, bit-exact.
  void setAnalogTop(double depth) { analogTop_.setDepth(depth); }

  // Interleaved float I/O. In-place (in == out) is allowed. Allocation-free.
  void process(const float* in, float* out, int frames);

  // Response of the configured curve, including preamp and auto headroom.
  double responseDb(int channel, double freqHz) const;
  // Response of the bands alone (no preamp/headroom): what a UI draws.
  double eqResponseDb(int channel, double freqHz) const { return eq_.responseDb(channel, freqHz); }
  double appliedGainDb() const { return lab_ ? cfg_.lab.inputGainDb : gainDb_.load(); }
  // Current attenuation from Automatic Gain Protection (<= 0 dB).
  double gainProtectionDb() const { return agpDb_.load(); }
  void resetGainProtection() { agpDb_.store(0.0);gainResetPending_.store(true); }
  int latencyFrames() const;

  // Svaramanas: analyse the *input* (what the source app plays) in process().
  void setAnalysisEnabled(bool on) { analysisOn_.store(on); }
  SourceFeatures analysis() const { return analyzer_.snapshot(); }

  void reset();

 private:
  void updateGain();

  EngineConfig cfg_;
  std::atomic<bool> autoHeadroom_;
  std::atomic<bool> gainProtection_;
  ParametricEq eq_;  // runs at sampleRate * oversample
  std::unique_ptr<LabEq> lab_;
  std::vector<std::unique_ptr<Oversampler>> os_;
  std::vector<Dither> dither_;
  std::atomic<double> userPreampDb_{0.0};
  std::atomic<double> gainDb_{0.0};
  std::atomic<double> agpDb_{0.0};
  std::atomic<bool> gainResetPending_{false};
  std::atomic<double> bassCharacter_{0.0};
  std::atomic<double> bassCrossover_{120.0};
  std::atomic<double> bassResolve_{0.0};
  double appliedBassCrossover_ = 120.0;
  BassShaper bass_;
  BassUnmask unmask_;
  TruePeakLimiter limiter_;
  DynamicEq dynamic_;
  std::atomic<double> dynamicAmount_{0};
  std::atomic<double> dynamicDb_[4]{};
  StereoTuner stereo_;
  Grounding grounding_;
  BassTexture texture_;
  ShrillGuard shrill_;
  AnalogTop analogTop_;
  std::atomic<bool> analysisOn_{false};
  SourceAnalyzer analyzer_;
  double smoothedGain_ = 1.0, gainTarget_ = 1.0, gainStep_ = 0.0;
  int gainRampRemaining_ = 0;
  bool gainInitialized_ = false;
  std::vector<double> outBuf_, high_, gains_;  // preallocated processing scratch
};

}  // namespace eqcore

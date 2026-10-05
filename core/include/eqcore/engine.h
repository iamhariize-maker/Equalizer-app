#pragma once
// The full processing chain used by both the capture engine (Android) and
// any future player mode:
//
//   float in -> 64-bit -> preamp (+ auto headroom) -> [oversample up]
//            -> parametric EQ at the high rate -> [oversample down]
//            -> bass character (punch/sustain) -> vocal tuner / instrument amp (M/S)
//            -> gain protection
//            -> dither to the output word length -> float out
//
// QualityMode::Audiophile spends CPU on precision: EQ runs at 4x (or 8x) the
// sample rate so high-frequency bands keep their analog shape instead of
// being "cramped" near Nyquist, and the output is TPDF-dithered.
#include <atomic>
#include <memory>
#include <vector>

#include "eqcore/analyzer.h"
#include "eqcore/bass.h"
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
  int maxBlock = 1024;       // frames per internal chunk

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
  void setBassCharacter(double character, double crossoverHz = 120.0);
  // Vocal tuner + instrument amplifier (stereo engines only; mono ignores it).
  void setStereoTuner(const StereoTunerParams& p) { stereo_.setParams(p); }

  // Interleaved float I/O. In-place (in == out) is allowed. Allocation-free.
  void process(const float* in, float* out, int frames);

  // Response of the configured curve, including preamp and auto headroom.
  double responseDb(int channel, double freqHz) const;
  // Response of the bands alone (no preamp/headroom): what a UI draws.
  double eqResponseDb(int channel, double freqHz) const { return eq_.responseDb(channel, freqHz); }
  double appliedGainDb() const { return gainDb_.load(); }
  // Current attenuation from Automatic Gain Protection (<= 0 dB).
  double gainProtectionDb() const { return agpDb_.load(); }
  void resetGainProtection() { agpDb_.store(0.0); }
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
  std::vector<std::unique_ptr<Oversampler>> os_;
  std::vector<Dither> dither_;
  std::atomic<double> userPreampDb_{0.0};
  std::atomic<double> gainDb_{0.0};
  std::atomic<double> agpDb_{0.0};
  std::atomic<double> bassCharacter_{0.0};
  std::atomic<double> bassCrossover_{120.0};
  double appliedBassCrossover_ = 120.0;
  BassShaper bass_;
  StereoTuner stereo_;
  std::atomic<bool> analysisOn_{false};
  SourceAnalyzer analyzer_;
  double smoothedGain_ = 1.0, gainTarget_ = 1.0, gainStep_ = 0.0;
  int gainRampRemaining_ = 0;
  bool gainInitialized_ = false;
  std::vector<double> outBuf_, high_, gains_;  // preallocated processing scratch
};

}  // namespace eqcore

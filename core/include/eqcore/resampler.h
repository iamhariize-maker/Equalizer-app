#pragma once
// Rational polyphase sample-rate converter (e.g. 44.1k <-> 48k <-> 96k <-> 192k)
// with Neutron-style Quality / Audiophile settings.
//
// The interpolation kernel is a Kaiser-windowed sinc evaluated in 64-bit. The
// Audiophile setting keeps the passband flat closer to Nyquist and rejects
// aliasing far deeper, at the price of a ~4x longer kernel (more CPU).
#include <memory>
#include <vector>

namespace eqcore {

enum class ResamplerQuality {
  Quality,     // flat to 0.86*Nyquist(min rate), >= 100 dB alias rejection
  Audiophile,  // flat to 0.95*Nyquist(min rate), >= 140 dB alias rejection
};

class Resampler {
 public:
  // Throws std::invalid_argument for non-integer rates or ratios whose reduced
  // interpolation factor exceeds 2048 (every standard audio rate pair is fine).
  Resampler(int inputRate, int outputRate, ResamplerQuality quality);

  int inputRate() const { return inRate_; }
  int outputRate() const { return outRate_; }
  int tapsPerPhase() const { return K_; }
  int phases() const { return L_; }
  // Group delay in input samples (fractional).
  double latencyInputSamples() const;
  // Upper bound on outputs produced for `nIn` inputs.
  int maxOutputFor(int nIn) const;

  // Consumes all of `in`, writes the produced samples to `out`, returns their
  // count (never more than maxOutputFor(nIn)). Allocation-free.
  int process(const double* in, int nIn, double* out);

  void reset();

 private:
  int inRate_, outRate_;
  int L_ = 1, M_ = 1, K_ = 0;
  std::shared_ptr<const std::vector<double>> table_;  // [phase][tap], shareable across channels
  std::vector<double> hist_;  // double-written ring of the last K_ inputs
  int pos_ = 0;
  int phase_ = 0;  // (n * M) mod L for the next output
  long long pending_ = 0;  // inputs still to consume before the next output
};

}  // namespace eqcore

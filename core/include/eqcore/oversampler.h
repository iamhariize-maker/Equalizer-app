#pragma once
// Cascaded 2x polyphase/Kaiser-windowed-sinc oversampler (1x, 2x, 4x, 8x).
//
// Every stage is a linear-phase FIR designed for `stopbandDb` attenuation and
// a flat passband up to `passbandHz`. The cost is deliberately high: this is
// the "audiophile" quality path.
#include <vector>

namespace eqcore {

struct OversamplerSpec {
  int factor = 1;               // 1, 2, 4 or 8
  double baseSampleRate = 44100.0;
  double passbandHz = 20000.0;  // flat up to here (clamped below Nyquist)
  double stopbandDb = 120.0;
  int maxBlock = 1024;          // largest block passed to up()/down()
};

class Oversampler {
 public:
  explicit Oversampler(const OversamplerSpec& spec);

  int factor() const { return spec_.factor; }
  // Round-trip (up then down) delay, in base-rate samples. Always an integer.
  int latencySamples() const { return latency_; }
  // Total FIR taps across all stages (a proxy for CPU cost).
  int totalTaps() const;

  // in: n samples at the base rate -> out: n * factor samples.
  void up(const double* in, int n, double* out);
  // in: n * factor samples -> out: n samples at the base rate.
  void down(const double* in, int n, double* out);

  void reset();

 public:
  struct Stage {
    std::vector<double> h;       // symmetric FIR, odd length
    // interpolator
    std::vector<double> phase0, phase1;
    std::vector<double> upHist;  // double-written ring
    int upLen = 0, upPos = 0;
    // decimator
    std::vector<double> downHist;
    int downPos = 0;
  };

 private:
  void upStage(Stage& s, const double* in, int n, double* out);
  void downStage(Stage& s, const double* in, int n, double* out);

  OversamplerSpec spec_;
  std::vector<Stage> stages_;
  int latency_ = 0;
  std::vector<double> bufA_, bufB_;
};

}  // namespace eqcore

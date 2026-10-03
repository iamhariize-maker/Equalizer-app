#pragma once
// Final word-length reduction: TPDF dither, optionally noise-shaped.
#include <cstdint>

namespace eqcore {

enum class DitherMode {
  None,        // plain rounding to the target grid
  Tpdf,        // triangular dither, +-1 LSB: error is signal-independent
  ShapedTpdf,  // TPDF + 1st-order error feedback, NTF = 1 - z^-1 (noise moved up in frequency)
};

class Dither {
 public:
  Dither(int bits = 0, DitherMode mode = DitherMode::Tpdf, uint64_t seed = 0x9E3779B97F4A7C15ull);

  // bits == 0 disables quantisation entirely (pass-through).
  int bits() const { return bits_; }
  DitherMode mode() const { return mode_; }

  // Quantises one sample (full scale +-1.0) to a `bits`-bit grid.
  double process(double x);
  void reset() { err_ = 0.0; }

 private:
  double uniform();  // [0, 1)

  int bits_;
  DitherMode mode_;
  double lsb_ = 0.0, scale_ = 0.0;
  uint64_t rng_;
  double err_ = 0.0;
};

}  // namespace eqcore

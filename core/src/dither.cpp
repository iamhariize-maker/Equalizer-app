#include "eqcore/dither.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

Dither::Dither(int bits, DitherMode mode, uint64_t seed)
    : bits_(std::clamp(bits, 0, 32)), mode_(mode), rng_(seed ? seed : 1) {
  if (bits_ > 0) {
    scale_ = std::ldexp(1.0, bits_ - 1);  // e.g. 32768 for 16-bit
    lsb_ = 1.0 / scale_;
  }
}

double Dither::uniform() {
  // xorshift64*: fast, good enough for dither, deterministic for tests.
  rng_ ^= rng_ >> 12;
  rng_ ^= rng_ << 25;
  rng_ ^= rng_ >> 27;
  const uint64_t r = rng_ * 0x2545F4914F6CDD1Dull;
  return static_cast<double>(r >> 11) * (1.0 / 9007199254740992.0);
}

double Dither::process(double x) {
  if (bits_ == 0) return x;
  // Error feedback: u = x - e[n-1]; y = Q(u + d); e[n] = y - u  =>  y = x + e[n] - e[n-1]
  const double u = (mode_ == DitherMode::ShapedTpdf) ? x - err_ : x;
  double d = 0.0;
  if (mode_ != DitherMode::None) d = (uniform() - uniform()) * lsb_;
  double y = std::nearbyint((u + d) * scale_);
  y = std::clamp(y, -scale_, scale_ - 1.0) * lsb_;
  if (mode_ == DitherMode::ShapedTpdf) {
    // Clamp the fed-back error so clipping can't make the loop run away.
    err_ = std::clamp(y - u, -2.0 * lsb_, 2.0 * lsb_);
  }
  return y;
}

}  // namespace eqcore

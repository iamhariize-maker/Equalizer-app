#pragma once
// RBJ "Audio EQ Cookbook" biquads, evaluated in 64-bit float.
#include <complex>

namespace eqcore {

enum class FilterType {
  Peak,
  LowShelf,
  HighShelf,
  LowPass,
  HighPass,
  BandPass,  // constant 0 dB peak gain
  Notch,
  AllPass,
};

struct BandParams {
  FilterType type = FilterType::Peak;
  double freqHz = 1000.0;
  double gainDb = 0.0;  // used by Peak / LowShelf / HighShelf only
  double q = 0.7071067811865476;
  bool enabled = true;
};

// Normalised so that a0 == 1:  y = b0 x + b1 x[-1] + b2 x[-2] - a1 y[-1] - a2 y[-2]
struct BiquadCoeffs {
  double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
};

// Frequency is clamped to (0, 0.499 * sampleRate) and Q to >= 0.01 so that any
// user input yields a stable filter.
BiquadCoeffs designBiquad(const BandParams& p, double sampleRate);

std::complex<double> responseAt(const BiquadCoeffs& c, double freqHz, double sampleRate);
double magnitudeDb(const BiquadCoeffs& c, double freqHz, double sampleRate);

// True when the band changes nothing (disabled, or 0 dB peak/shelf).
bool isIdentityBand(const BandParams& p);

// Transposed direct form II. Better numerical behaviour than DF1/DF2 for
// low-frequency, high-Q sections.
class Biquad {
 public:
  void setCoeffs(const BiquadCoeffs& c) { c_ = c; }
  void reset() { z1_ = z2_ = 0.0; }
  double process(double x) {
    const double y = c_.b0 * x + z1_;
    z1_ = c_.b1 * x - c_.a1 * y + z2_;
    z2_ = c_.b2 * x - c_.a2 * y;
    return y;
  }

 private:
  BiquadCoeffs c_;
  double z1_ = 0.0, z2_ = 0.0;
};

}  // namespace eqcore

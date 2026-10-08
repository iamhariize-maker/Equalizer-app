#include "eqcore/biquad.h"

#include <algorithm>
#include <cmath>

namespace eqcore {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

bool isIdentityBand(const BandParams& p) {
  if (!p.enabled) return true;
  switch (p.type) {
    case FilterType::Peak:
    case FilterType::LowShelf:
    case FilterType::HighShelf:
      return std::fabs(p.gainDb) < 1e-9;
    default:
      return false;
  }
}

BiquadCoeffs designBiquad(const BandParams& p, double fs) {
  // Every input is sanitised: presets, imports and automation must never put a
  // non-finite or extreme value into the audio path.
  const double f = std::clamp(std::isfinite(p.freqHz) ? p.freqHz : 1000.0, 1e-3, 0.499 * fs);
  const double q = std::isfinite(p.q) ? std::max(p.q, 0.01) : 0.7071;
  const double gainDb = std::isfinite(p.gainDb) ? std::clamp(p.gainDb, -kMaxBandGainDb, kMaxBandGainDb) : 0.0;
  const double w0 = 2.0 * kPi * f / fs;
  const double cw = std::cos(w0);
  const double sw = std::sin(w0);
  const double alpha = sw / (2.0 * q);
  const double A = std::pow(10.0, gainDb / 40.0);

  double b0, b1, b2, a0, a1, a2;
  switch (p.type) {
    case FilterType::Peak:
      b0 = 1.0 + alpha * A;
      b1 = -2.0 * cw;
      b2 = 1.0 - alpha * A;
      a0 = 1.0 + alpha / A;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha / A;
      break;
    case FilterType::LowShelf: {
      const double s = 2.0 * std::sqrt(A) * alpha;
      b0 = A * ((A + 1) - (A - 1) * cw + s);
      b1 = 2.0 * A * ((A - 1) - (A + 1) * cw);
      b2 = A * ((A + 1) - (A - 1) * cw - s);
      a0 = (A + 1) + (A - 1) * cw + s;
      a1 = -2.0 * ((A - 1) + (A + 1) * cw);
      a2 = (A + 1) + (A - 1) * cw - s;
      break;
    }
    case FilterType::HighShelf: {
      const double s = 2.0 * std::sqrt(A) * alpha;
      b0 = A * ((A + 1) + (A - 1) * cw + s);
      b1 = -2.0 * A * ((A - 1) + (A + 1) * cw);
      b2 = A * ((A + 1) + (A - 1) * cw - s);
      a0 = (A + 1) - (A - 1) * cw + s;
      a1 = 2.0 * ((A - 1) - (A + 1) * cw);
      a2 = (A + 1) - (A - 1) * cw - s;
      break;
    }
    case FilterType::LowPass:
      b0 = (1.0 - cw) / 2.0;
      b1 = 1.0 - cw;
      b2 = (1.0 - cw) / 2.0;
      a0 = 1.0 + alpha;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha;
      break;
    case FilterType::HighPass:
      b0 = (1.0 + cw) / 2.0;
      b1 = -(1.0 + cw);
      b2 = (1.0 + cw) / 2.0;
      a0 = 1.0 + alpha;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha;
      break;
    case FilterType::BandPass:
      b0 = alpha;
      b1 = 0.0;
      b2 = -alpha;
      a0 = 1.0 + alpha;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha;
      break;
    case FilterType::Notch:
      b0 = 1.0;
      b1 = -2.0 * cw;
      b2 = 1.0;
      a0 = 1.0 + alpha;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha;
      break;
    case FilterType::AllPass:
    default:
      b0 = 1.0 - alpha;
      b1 = -2.0 * cw;
      b2 = 1.0 + alpha;
      a0 = 1.0 + alpha;
      a1 = -2.0 * cw;
      a2 = 1.0 - alpha;
      break;
  }
  return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

std::complex<double> responseAt(const BiquadCoeffs& c, double freqHz, double fs) {
  const double w = 2.0 * kPi * freqHz / fs;
  const std::complex<double> z1 = std::polar(1.0, -w);
  const std::complex<double> z2 = z1 * z1;
  return (c.b0 + c.b1 * z1 + c.b2 * z2) / (1.0 + c.a1 * z1 + c.a2 * z2);
}

double magnitudeDb(const BiquadCoeffs& c, double freqHz, double fs) {
  return 20.0 * std::log10(std::max(std::abs(responseAt(c, freqHz, fs)), 1e-300));
}

}  // namespace eqcore

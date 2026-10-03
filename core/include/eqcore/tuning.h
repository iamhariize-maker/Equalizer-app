#pragma once
// Headphone tuning: frequency-response curves, target - measurement
// corrections, and a high-resolution fit onto dense bell bands.
//
// This is the AutoEq idea, done on-device so any target can be applied to
// any measured headphone, and fitted with many bands (60+) instead of a
// handful of parametric filters.
#include <string>
#include <vector>

#include "eqcore/biquad.h"

namespace eqcore {

struct FrCurve {
  std::vector<double> hz;  // ascending
  std::vector<double> db;

  bool empty() const { return hz.size() < 2; }
  // Log-frequency linear interpolation; clamps outside the measured range.
  double at(double f) const;
};

// Parses AutoEq CSV ("frequency,raw,..." header) and Squiglink/REW style
// text ("20 -3.2" per line; tabs, spaces, commas or semicolons; comment and
// header lines ignored). Returns an empty curve if fewer than 2 points.
FrCurve parseCurve(const std::string& text);

// Fractional-octave smoothing (e.g. 1/6), evaluated on the curve's own points.
FrCurve smooth(const FrCurve& c, double octaveFraction);

struct TuningOptions {
  double bassDb = 0.0;          // added to the target as a 105 Hz low shelf
  double tiltDbPerOct = 0.0;    // added to the target, pivot 1 kHz (+ = brighter)
  double maxBoostDb = 10.0;     // correction never boosts more than this
  double maxCutDb = 20.0;
  double trebleSmoothFromHz = 6000.0;  // above: heavy smoothing (ear-dependent region)
  double fadeFromHz = 14000.0;  // correction fades to 0 dB from here...
  double fadeToHz = 20000.0;    // ...to here (measurements are unreliable up there)
};

// Correction (dB) on a log grid 20 Hz..20 kHz with `points` points:
//   (target + bass + tilt) - measurement, level-aligned over 300 Hz..3 kHz,
//   smoothed, limited, faded at the top.
FrCurve computeCorrection(const FrCurve& measurement, const FrCurve& target,
                          const TuningOptions& opt = {}, int points = 256);

struct DenseFit {
  std::vector<BandParams> bands;
  double rmsErrorDb = 0.0;  // fit error over 30 Hz..14 kHz
  double maxErrorDb = 0.0;
};

// Fits `curve` with `bandCount` log-spaced bell filters (20 Hz..20 kHz, Q set
// by the spacing) by iterating on the exact biquad responses.
DenseFit fitDenseBands(const FrCurve& curve, int bandCount, double sampleRate = 48000.0);

}  // namespace eqcore

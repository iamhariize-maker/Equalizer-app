#pragma once
// Parsers for AutoEq / Equalizer APO text formats.
#include <string>
#include <utility>
#include <vector>

#include "eqcore/biquad.h"

namespace eqcore {

struct ParametricPreset {
  double preampDb = 0.0;
  std::vector<BandParams> bands;
  std::vector<std::string> warnings;  // lines that were skipped, and why
};

// "ParametricEQ.txt" format, e.g.
//   Preamp: -6.2 dB
//   Filter 1: ON PK Fc 105 Hz Gain 4.5 dB Q 0.70
//   Filter 2: ON LSC Fc 105 Hz Gain 5.0 dB Q 0.70
// Supported types: PK/PEQ, LS/LSC, HS/HSC, LP/LPQ, HP/HPQ, BP, NO, AP.
ParametricPreset parseParametricEq(const std::string& text);

// "GraphicEQ: 20 -7.9; 21 -7.9; ..." -> (freqHz, gainDb) pairs, sorted by frequency.
// This is what a gain-per-band engine such as Android DynamicsProcessing consumes.
std::vector<std::pair<double, double>> parseGraphicEq(const std::string& text);

}  // namespace eqcore

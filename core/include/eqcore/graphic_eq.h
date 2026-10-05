#pragma once
#include "eqcore/biquad.h"
#include <vector>
namespace eqcore {
struct GraphicFit {
  std::vector<BandParams> bands;
  double rmsDb = 0.0;
  double maxDb = 0.0;
};
// Control-thread conversion: fits the RESPONSE, not centre samples interpreted as filter gains.
// Fixed 31.25 Hz–16 kHz centres, spacing-derived bell Q, shelf endpoints, +/-12 dB gains.
// Error measured on a 240-point log grid, 20 Hz–20 kHz. No audio-thread work.
GraphicFit fitGraphicEq(const std::vector<BandParams>& target, int count, double sampleRate = 48000.0);
// Uniformly reduce only positive gains if their summed response exceeds the boost ceiling.
double positiveEqOverlapScale(const std::vector<BandParams>& bands, double ceilingDb = 6.0, double sampleRate = 48000.0);
}

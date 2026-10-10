// What Svaramanas' loudness trim does before and after Engine B has heard enough music.
// Svaramanas sets preamp = -delta, where delta is predictedGuideLoudnessDeltaDb() for the bands it
// applies. Without analysed audio the estimate uses a pink reference; once SourceFeatures::valid is
// true (3 s of audio) the measured spectrum replaces it in one step. The measured spectra below are
// synthetic tilts (a stand-in for music), not recordings.
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "eqcore/analyzer.h"
#include "eqcore/biquad.h"
#include "eqcore/stereo.h"
#include "eqcore/svaramanas.h"

using namespace eqcore;
using namespace eqcore::svaramanas;

namespace {

// The quiet-listening layer from SvaresaBrain (bass shelf at 110 Hz, treble shelf at 7.5 kHz).
std::vector<BandParams> quietListeningLayer(double bassDb, double trebleDb) {
  return {
      {FilterType::LowShelf, 110.0, bassDb, 0.71, true},
      {FilterType::HighShelf, 7500.0, trebleDb, 0.71, true},
      {FilterType::LowShelf, 55.0, 0.0, 0.71, true},
      {FilterType::Peak, 3800.0, 0.0, 1.0, true},
  };
}

SourceFeatures syntheticTrack(double tiltDbPerOct, double sideToMidDb) {
  SourceFeatures f;
  f.valid = true;
  f.seconds = 30.0;
  f.sideToMidDb = sideToMidDb;
  for (int i = 0; i < SourceFeatures::kBands; ++i)
    f.bandDb[static_cast<size_t>(i)] = tiltDbPerOct * std::log2(SourceFeatures::bandCentreHz(i) / 1000.0);
  return f;
}

// Svaramanas.recompute(): the preamp is -delta, clamped to [-18, +1.5] dB.
double preampFor(double deltaDb) { return std::fmin(1.5, std::fmax(-18.0, -deltaDb)); }

}  // namespace

int main() {
  const StereoTunerParams stereoOff{};
  std::printf("%-22s %-28s %10s %10s %14s\n", "quiet-listening lift", "analysis state", "delta dB", "preamp dB", "change dB");
  for (double bass : {2.0, 4.0, 6.0}) {
    const double treble = bass / 2.0;
    const auto bands = quietListeningLayer(bass, treble);
    const double estimate = predictedGuideLoudnessDeltaDb(bands, stereoOff, nullptr);
    char lift[32];
    std::snprintf(lift, sizeof lift, "bass %.0f dB, treble %.0f dB", bass, treble);
    std::printf("%-22s %-28s %10.2f %10.2f %14s\n", lift, "pink estimate (applied at once)", estimate, preampFor(estimate), "-");
    for (double tilt : {-3.0, -4.5, -6.0}) {
      const SourceFeatures track = syntheticTrack(tilt, -6.0);
      const double measured = predictedGuideLoudnessDeltaDb(bands, stereoOff, &track);
      char state[48];
      std::snprintf(state, sizeof state, "measured, %.1f dB/octave", tilt);
      std::printf("%-22s %-28s %10.2f %10.2f %+14.2f\n", "", state, measured, preampFor(measured),
                  preampFor(measured) - preampFor(estimate));
    }
  }
  return 0;
}

#pragma once
// Svaramanas — Svan's sound intelligence (docs/SMART.md).
//
// A deterministic policy that turns (what the listener asked for) + (what the
// SourceAnalyzer heard) into a bounded "smart layer": a few parametric bands,
// a loudness-matching trim and suggestions for the bass/stereo tuners.
// Priorities, highest first:
//   1. Do no harm: bounded gains, a fixed emphasis budget, loudness-matched.
//   2. The listener's request (feel + up to 3-4 instrument categories).
//   3. Clarity: trim mud / boom / harshness the analyser actually measured.
//   4. Respect the music: small corrections only, nothing above a lossy ceiling,
//      no stereo widening of mono sources, gentler on already-crushed masters.
//   5. Explain: every adjustment carries a reason code for the UI.
// The output always has the same band skeleton for the same request, so an
// adaptive caller can slew band gains index by index.
#include <cstdint>
#include <vector>

#include "eqcore/analyzer.h"
#include "eqcore/biquad.h"
#include "eqcore/stereo.h"

namespace eqcore::svaramanas {

enum class Feel : int { Balanced = 0, Warm, Bright, Punchy, Spacious, Intimate };

// Instrument categories (bit mask).
enum Category : uint32_t {
  kVocals = 1u << 0,
  kStrings = 1u << 1,   // strings & orchestra
  kPiano = 1u << 2,     // piano & keys
  kGuitars = 1u << 3,
  kDrums = 1u << 4,     // drums & percussion
  kBass = 1u << 5,
  kBrass = 1u << 6,     // brass & winds
  kSynth = 1u << 7,     // synth & electronic
  kSpace = 1u << 8,     // space & ambience (stereo, not a frequency region)
};
constexpr int kCategoryCount = 9;
constexpr int kMaxCategories = 4;

// Reason codes shown to the listener (the UI owns the wording).
enum Note : int {
  kNoteFeel = 1,
  kNoteCategories = 2,
  kNoteMud = 10,
  kNoteBoom = 11,
  kNoteHarsh = 12,
  kNoteDull = 13,
  kNoteLossy = 14,          // boosts kept below the codec ceiling
  kNoteCrushedMaster = 15,  // heavily limited / clipping master: boosts halved
  kNoteMono = 16,           // no side signal: widening skipped
  kNoteBright = 17,         // Svaresa: mix thinner/brighter than a healthy balance, eased
  kNoteDark = 18,           // Svaresa: mix darker/heavier than a healthy balance, opened
  kNoteBudget = 20,         // emphasis scaled to fit the budget
  kNoteConflictDropped = 21,
  kNoteOverlapSoftened = 22,
  kNoteLoudnessMatched = 30,
  kNoteListening = 31,      // not enough audio heard yet: static plan
};

struct Request {
  Feel feel = Feel::Balanced;
  uint32_t categories = 0;  // in pick order: see order[]
  // Pick order matters (the first three always win). Categories listed here,
  // most important first; bits missing from it are appended in bit order.
  std::vector<uint32_t> order;
  double strength = 1.0;    // 0..1.5
  bool stereoEngine = true; // Engine B (mid/side tuners available)
  // Svaresa is the automatic master mode. It ignores guided taste/category
  // lifts and acts only on source evidence that was measured, but with real
  // authority: wider correction limits, a tonal-balance (tilt) correction and
  // a harshness-driven smoothing suggestion that guided mode does not have.
  bool svaresaMode = false;
};

struct CategoryCheck {
  uint32_t accepted = 0;
  uint32_t rejected = 0;        // over the limit or conflicting
  uint32_t conflictWith = 0;    // categories the rejected one clashed with
};

// Applies the 3-4 rule: the first three picks always stand; a fourth only if its
// main region does not overlap theirs; more than four never.
CategoryCheck checkCategories(const Request& r);

struct Plan {
  std::vector<BandParams> bands;  // the smart layer (fixed skeleton per request)
  double preampDb = 0.0;          // loudness-matching trim (<= 0 for boosts)
  double predictedDeltaDb = 0.0;  // estimated static guide loudness change before trim
  double bassCharacter = 0.0;     // suggestion, -1..1
  StereoTunerParams stereo{};     // suggestion (Engine B only)
  CategoryCheck categories;
  std::vector<int> notes;
};

// Guardrail constants (also asserted by tests).
constexpr double kMaxBandDb = 3.0;         // any single smart band
constexpr double kMaxCorrectionDb = 2.5;   // analyser-driven cuts/lifts (guided mode)
constexpr double kSvaresaMaxCorrectionDb = 4.0;  // Svaresa's measured cuts
constexpr double kSvaresaTiltTargetDbPerOct = -2.5;  // healthy third-octave balance (energy per band vs octave)
constexpr double kSvaresaTiltDeadbandDbPerOct = 1.5; // no tilt move inside +-1.5 of the target
constexpr double kSvaresaMaxTiltDb = 2.5;          // largest tilt shelf pair move
constexpr double kEmphasisBudgetDb = 6.0;  // sum of positive request gains

// features may be null (Engine A / nothing heard yet): a static plan matched
// against a pink reference spectrum.
Plan plan(const Request& r, const SourceFeatures* features);

// K-weighted loudness change of `bands` on a third-octave power spectrum
// (dB levels at SourceFeatures::bandCentreHz). Pink when spectrumDb is null.
// Full static guide (EQ + M/S), using measured M/S spectra when available.
// Without audio, pink with a 25% side-energy reference is an estimate only.
double predictedGuideLoudnessDeltaDb(const std::vector<BandParams>& bands, const StereoTunerParams& stereo,
                                     const SourceFeatures* features, double sampleRate = 48000.0);

double predictedLoudnessDeltaDb(const std::vector<BandParams>& bands, const double* spectrumDb,
                                double sampleRate = 48000.0);

}  // namespace eqcore::svaramanas

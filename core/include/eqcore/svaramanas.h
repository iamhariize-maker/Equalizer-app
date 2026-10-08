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
#include "eqcore/policy.h"
#include "eqcore/grounding.h"
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
  kNoteGrounded = 19,       // Svaresa: top-end spikes restrained / body added to ground the mix
  kNoteVoicing = 24,        // Svaresa: house voicing (deep foundation, warm body, softened top) applied
  kNotePunch = 25,          // Svaresa: limited master, a little bass punch restored
  kNoteAtmosphere = 26,     // Svaresa: narrow mix, a little side ambience opened
  kNoteTaste = 27,          // Svaresa: voicing targets come from the listener's learned reference tracks
  kNoteBudget = 20,         // emphasis scaled to fit the budget
  kNoteConflictDropped = 21,
  kNoteOverlapSoftened = 22,
  kNoteLoudnessMatched = 30,
  kNoteListening = 31,      // not enough audio heard yet: static plan
};

// The listener's learned "this is how I want it to sound": a running average of what Svaramanas measured on
// reference tracks the listener chose ("Learn this sound"). Features only, never audio; stored on the phone.
// When valid, Svaresa uses these as its targets instead of the built-in house values.
struct TasteTarget {
  bool valid = false;
  int tracks = 0;
  double tiltDbPerOct = -2.5;   // overall third-octave slope
  double bassToMidsDb = 0.0;    // bassToMidsDb(): 40-100 Hz vs 200 Hz-2 kHz band levels
  double sharpness = 1.0;       // relativeSharpness()
  double sideToMidDb = -12.0;   // stereo side energy vs mid
  double plrDb = 0.0;           // peak-to-loudness ratio
  static constexpr int kPacked = 7;
  void pack(double* out) const;
  static TasteTarget unpack(const double* in, int n);
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
  // lifts and acts on source evidence that was measured, but with real
  // authority: wider correction limits, a tonal-balance (tilt) correction and
  // a harshness-driven smoothing suggestion that guided mode does not have.
  // On top of the measured corrections it applies the owner's "grounded" house
  // voicing (foundation, body, softness, punch, atmosphere and Plan::grounding).
  bool svaresaMode = false;
  // Identity of the evidence in `features` (see policy.h). The defaults describe fresh, same-epoch,
  // fully confident evidence, which is what callers that do not track identity have always assumed.
  uint64_t epoch = 0;                // the capture/route/format/parameter epoch the plan is for
  uint64_t featuresEpoch = 0;        // the epoch the features were measured in
  double featuresAgeSeconds = 0.0;   // how old the analysed window is
  double featuresConfidence = 1.0;   // 0..1
  // Output is the phone's own speaker: no foundation lift (a small driver cannot reproduce it).
  bool speakerRoute = false;
  // Optional learned targets (Svaresa only); null = the built-in house voicing.
  const TasteTarget* taste = nullptr;
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
  GroundingParams grounding{};    // Svaresa only: organic-body identity (docs/SONIC_IDENTITY.md)
  CategoryCheck categories;
  std::vector<int> notes;
  // Evidence gate outcome for every registry rule that was consulted while planning from `features`.
  struct Gate {
    const char* rule;
    policy::Skip skip;  // Skip::None = admitted
  };
  std::vector<Gate> gates;
};

// Guardrail constants (also asserted by tests).
constexpr double kMaxBandDb = 3.0;         // any single smart band
constexpr double kMaxCorrectionDb = 2.5;   // analyser-driven cuts/lifts (guided mode)
constexpr double kSvaresaMaxCorrectionDb = 4.0;  // Svaresa's measured cuts
constexpr double kSvaresaTiltTargetDbPerOct = -2.5;  // healthy third-octave balance (energy per band vs octave)
constexpr double kSvaresaTiltDeadbandDbPerOct = 1.5; // no tilt move inside +-1.5 of the target
constexpr double kSvaresaMaxTiltDb = 2.5;          // largest tilt shelf pair move
// Svaresa's "grounded" voicing (owner-chosen identity, docs/SONIC_IDENTITY.md): a small constant
// body and restraint, raised only by measured top-end excess. Depth is 0..1 of Grounding's own caps.
constexpr double kGroundingBaseBody = 0.7;
constexpr double kGroundingBaseRestraint = 0.25;
constexpr double kGroundingMaxBody = 1.0;
// House voicing: a clearly audible, owner-chosen shape that Svaresa applies on top of its measured corrections
// (docs/SONIC_IDENTITY.md). Owner's targets: deep, clean bass like A. R. Rahman / Massive Attack; natural
// transients and atmosphere like Wilco.
//  * Foundation: a low shelf at 65 Hz that lifts only what the track lacks against the target bass-to-mids
//    balance (a healthy balance plus kHouseDeepBassDb, or the learned taste). A bass-heavy track gets nothing.
//  * Body: a small warm bell at 180 Hz (voice chest, guitar body), backing off with measured mud/boom.
//  * Softness: a high shelf at 8.5 kHz that deepens with measured sharpness excess.
//  * Punch: a little bass-envelope punch (BassShaper) on limited masters, which lose their kick first.
//  * Atmosphere: a little side ambience (StereoTuner space) when a mix is narrower than the target.
constexpr double kHouseDeepBassDb = 2.0;      // house target: this much more bass-to-mids than a healthy balance
constexpr double kFoundationMaxDb = 3.0;      // most the foundation shelf will lift
constexpr double kFoundationStaticDb = 1.0;   // before anything has been heard
constexpr double kHouseBodyDb = 0.75;
constexpr double kHouseSoftnessDb = -0.8;
constexpr double kPunchOnLimited = 0.12;      // bass character at fully limited (PLR <= 6 dB)
constexpr double kAtmosphereMax = 0.2;        // StereoTuner space (+1.2 dB side level)
constexpr double kHouseSideToMidDb = -12.0;   // target side-to-mid energy when no taste is learned
constexpr int kTasteMaxWeight = 12;           // a new reference always moves the learned target by >= 1/13
constexpr double kTasteMinSeconds = 20.0;     // seconds of music heard before a track can be learned
constexpr double kSoftnessMaxDb = -2.0;     // most the measured-sharpness term may add (on top of the house shelf)
constexpr double kSoftnessSlopeDb = 5.0;    // dB of extra softening per unit of sharpness excess over the healthy balance
constexpr double kSharpnessDeadband = 0.05; // fraction over the healthy-balance sharpness that is left alone
constexpr double kEmphasisBudgetDb = 6.0;  // sum of positive request gains

// Sharpness of a third-octave spectrum (von Bismarck / DIN 45692 style: specific loudness weighted toward the
// top Bark bands), as a RATIO to the sharpness of a healthy-balance spectrum (tilt kSvaresaTiltTargetDbPerOct).
// 1.0 = as sharp as a healthy balance; 1.3 = 30% sharper. An approximation from the analyser's band levels,
// not a calibrated acum value: use it relatively (docs/RESEARCH_GROUNDED_SOUND.md section 2).
double relativeSharpness(const SourceFeatures& f);

// Bass weight against the mids: mean band level 40-100 Hz minus mean band level 200 Hz-2 kHz (dB).
double bassToMidsDb(const SourceFeatures& f);
// bassToMidsDb() of a healthy-balance spectrum (tilt kSvaresaTiltTargetDbPerOct): about +8.3 dB.
double healthyBassToMidsDb();

// Adds one reference track to the learned taste (running mean, weight capped at kTasteMaxWeight). Returns `prev`
// unchanged when the features are not valid or less than kTasteMinSeconds of music was heard. A lossy source does
// not update the sharpness (its missing top would teach the wrong thing).
TasteTarget learnTaste(const TasteTarget& prev, const SourceFeatures& heard);

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

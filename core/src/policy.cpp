#include "eqcore/policy.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>

namespace eqcore::policy {

namespace {
const MetricSpec kMetrics[] = {
    {Metric::PeakDbfs, "peak", "dBFS", true, false},
    {Metric::LoudnessLufs, "loudness", "LUFS (K-weighted, gated)", true, false},
    {Metric::PlrDb, "peak-to-loudness", "dB", true, false},
    {Metric::ClipsPerSecond, "clipping rate", "runs/s", true, false},
    {Metric::Correlation, "L/R correlation", "-1..1", true, false},
    {Metric::SideToMidDb, "side-to-mid energy", "dB", true, false},
    {Metric::MonoLike, "mono-like", "0/1", true, false},
    {Metric::BandwidthCutoffHz, "inferred bandwidth", "Hz (not a codec label)", true, false},
    {Metric::TiltDbPerOct, "spectral tilt", "dB/octave", true, false},
    {Metric::MudDb, "mud excess", "dB over tilt line", true, false},
    {Metric::BoomDb, "boom excess", "dB over tilt line", true, false},
    {Metric::HarshDb, "harshness excess", "dB over tilt line", true, false},
    {Metric::AirDb, "air excess", "dB over tilt line", true, false},
    {Metric::BassNote, "validated bass note", "Hz with confidence", true, false},
    {Metric::LanePromDb, "lane prominence", "dB over the note", true, false},
    {Metric::ResidualCoherence, "M/S coherence", "0..1", true, false},
    {Metric::SideMidPower, "side/mid power", "ratio", true, false},
    {Metric::VolumeProxy, "music volume index", "0..1 (uncalibrated, not SPL)", false, true},
    {Metric::RouteHint, "output route hint", "enum from the audio server", false, true},
    {Metric::ClockMinutes, "local clock", "minutes after midnight", false, true},
    {Metric::BassToMidsDb, "bass-to-mids balance", "dB (40-100 Hz vs 200 Hz-2 kHz band levels)", true, false},
    {Metric::SharpnessRatio, "sharpness vs healthy balance", "ratio (1 = healthy; DIN 45692-style, approximate)", true, false},
};

using M = Metric;
// id, version, owner, inputs, n, minConf, maxAge s, sameEpoch, nativeOnly, allowProxy, parameter, units, min, max,
// competes, reason, rollback, counterexample
const std::vector<Rule> kRules = {
    {"SV-GAINPROT-1", 1, Owner::Fast, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 1, 0.0, 0.25, true, true, false,
     "output gain reduction", "dB", -60, 0,
     "Static EQ headroom shares one ledger; protection is the last authority and always wins",
     "Overload protection", "250 ms release back to unity",
     "core:automatic_gain_protection_catches_real_overloads", false},
    {"HEADROOM-1", 1, Owner::Fast, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 0, 0.0, 1.0, false, false, false,
     "combined positive EQ headroom", "dB", -24, 0,
     "Parametric/graphic/tuning/bass/smart layers are summed once; no layer budgets itself",
     "Headroom for the combined curve", "Restored when the boost is removed",
     "core:overlapping_eq_headroom_uses_the_combined_curve", false},
    {"SV-DYNEQ-1", 1, Owner::Fast, {M::LanePromDb, M::LanePromDb, M::LanePromDb}, 1, 0.0, 0.25, true, true, false,
     "dynamic EQ cut per lane", "dB", -1.5, 0,
     "120/330 Hz lanes yield to SV-UNMASK-1 while it is cutting; sum of lanes <= 3 dB",
     "A sustained resonance was softened", "250 ms release; exact bypass at amount 0",
     "core:dynamic_eq_is_reduction_only_selective_linked_and_exactly_bypassable"},
    {"SV-UNMASK-1", 1, Owner::Fast, {M::BassNote, M::LanePromDb, M::LanePromDb}, 2, 0.75, 0.5, true, true, false,
     "bass lane cut (70/110/180/280 Hz)", "dB", -2.0, 0,
     "Owns the low DynamicEq lanes while cutting; combined <= 2 dB; unknown note means no cut",
     "A sustained peak outside the bass note's harmonics was masking it", "50 ms onset release; 400 ms otherwise; exact bypass at 0",
     "core:bass_unmask_leaves_notes_tones_and_kicks_alone"},
    {"SV-RESOLVE-1", 1, Owner::Fast, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 0, 0.0, 1e9, false, true, false,
     "Bass Resolve level (Auto)", "0..1", 0, 1,
     "Raises the saved manual level only while Auto master is on; never lowers or overwrites it",
     "Protecting each bass note's shape (not a detector)", "Back to the saved manual value",
     "kotlin:model/BassTunerTest.kt#autoOwnershipNeverLowersOrOverwritesTheManualValue"},
    {"SV-SPATIAL-1", 1, Owner::Fast, {M::ResidualCoherence, M::SideMidPower, M::SideMidPower}, 2, 0.7, 0.3, true, true, false,
     "automatic side detail", "dB", 0, 4,
     "Space/Instruments are explicit and unbudgeted; this shares one 4 dB / half-mid side budget",
     "Detail present in the recording was brought forward", "Gain falls at once; recovers over 100 ms",
     "core:spatial_residual_leaves_coherent_and_already_wide_material_alone"},
    {"SV-QUIET-1", 1, Owner::Context, {M::VolumeProxy, M::VolumeProxy, M::VolumeProxy}, 1, 0.0, 5.0, false, false, true,
     "quiet-listening bass/treble lift", "dB", 0, 6,
     "Route protection caps bass on a phone speaker; manual EQ is added, never replaced",
     "Quiet listening: bass and treble restored against the mids (volume-to-loudness is an assumption)",
     "Slews to zero as volume rises; the listener can switch it off",
     "kotlin:SvaresaBrainTest.kt#liftsAreBoundedAndTheSpeakerIsProtected"},
    {"SV-SPEAKER-1", 1, Owner::Context, {M::RouteHint, M::RouteHint, M::RouteHint}, 1, 0.0, 60.0, false, false, true,
     "bass lift cap on phone speaker", "dB", 0, 1.5,
     "Caps SV-QUIET-1", "A small driver would only distort", "Cap lifts when the route changes",
     "kotlin:SvaresaBrainTest.kt#liftsAreBoundedAndTheSpeakerIsProtected"},
    {"SV-NIGHT-1", 1, Owner::Context, {M::ClockMinutes, M::ClockMinutes, M::ClockMinutes}, 1, 0.0, 120.0, false, false, true,
     "night comfort (sub-bass, presence, evening)", "dB", -2.5, 0,
     "Added to the context skeleton; forced On/Off by the listener always wins",
     "Night comfort", "Ramps out over an hour",
     "kotlin:SvaresaBrainTest.kt#nightFollowsTheClockWithSoftEdgesAndCanBeForced"},
    {"SM-TILT-1", 1, Owner::Slow, {M::TiltDbPerOct, M::PlrDb, M::PlrDb}, 1, 0.8, 10.0, true, true, false,
     "tilt shelf pair", "dB", -2.5, 2.5,
     "Dead band +-1.5 dB/oct around the target; manual curve and headphone tuning are kept",
     "Mix balance eased toward a healthy tonal balance", "Slews to zero when the mix is inside the dead band",
     "core:svaresa_moves_a_bright_or_dark_mix_toward_a_healthy_balance_within_bounds"},
    {"SM-BOOM-1", 1, Owner::Slow, {M::BoomDb, M::PlrDb, M::PlrDb}, 1, 0.8, 10.0, true, true, false,
     "63-125 Hz trim", "dB", -4, 0, "Shares the 6 dB emphasis budget; cut-only", "Boom trimmed",
     "Slews to zero", "core:svaramanas_trims_what_it_hears_and_leaves_a_clean_mix_alone"},
    {"SM-MUD-1", 1, Owner::Slow, {M::MudDb, M::PlrDb, M::PlrDb}, 1, 0.8, 10.0, true, true, false,
     "200-500 Hz trim", "dB", -4, 0, "Cut-only", "Mud trimmed", "Slews to zero",
     "core:svaramanas_trims_what_it_hears_and_leaves_a_clean_mix_alone"},
    {"SM-HARSH-1", 1, Owner::Slow, {M::HarshDb, M::HarshDb, M::HarshDb}, 1, 0.8, 10.0, true, true, false,
     "2.5-5 kHz trim and smoothing request", "dB", -4, 0, "Dynamic smoothing and EQ share the harshness range", "Shrillness softened",
     "Slews to zero", "core:svaresa_asks_for_harshness_smoothing_on_both_engines_when_the_mix_is_shrill"},
    {"SM-LOSSY-1", 1, Owner::Slow, {M::BandwidthCutoffHz, M::BandwidthCutoffHz, M::BandwidthCutoffHz}, 1, 0.8, 10.0, true, true, false,
     "no boost above the inferred bandwidth", "Hz", 0, 24000, "Limits boosts only; never a codec claim",
     "Boosts kept below where the file has real energy", "Limit lifts when bandwidth is re-measured",
     "core:svaramanas_respects_lossy_sources_mono_files_and_crushed_masters"},
    {"SM-CRUSH-1", 1, Owner::Slow, {M::PlrDb, M::ClipsPerSecond, M::ClipsPerSecond}, 2, 0.8, 10.0, true, true, false,
     "boost scale on a crushed master", "x", 0.5, 1.0, "Scales all positive smart gains", "Heavily limited master: boosts halved",
     "Back to 1.0 on a healthy master", "core:svaramanas_respects_lossy_sources_mono_files_and_crushed_masters"},
    {"SM-MONO-1", 1, Owner::Slow, {M::MonoLike, M::SideToMidDb, M::SideToMidDb}, 2, 0.8, 10.0, true, true, false,
     "stereo widening", "dB", 0, 0, "No widening of a mono-like source", "No side signal: widening skipped",
     "Resumes when side energy appears", "core:svaramanas_respects_lossy_sources_mono_files_and_crushed_masters"},
    {"SM-STEREO-1", 1, Owner::Slow, {M::SideToMidDb, M::Correlation, M::Correlation}, 2, 0.8, 10.0, true, true, false,
     "stereo guide matched to measured M/S spectra", "dB", -6, 6, "Space/Instruments explicit values win",
     "Side-only masking corrected", "Slews to zero",
     "core:svaramanas_matches_frequency_dependent_stereo_energy"},
    {"SM-LOUD-1", 1, Owner::Slow, {M::LoudnessLufs, M::PeakDbfs, M::PeakDbfs}, 1, 0.8, 10.0, true, true, false,
     "loudness-matching trim", "dB", -12, 0, "Applied through the single headroom ledger",
     "Level matched so a change is not just louder", "Moves with the combined curve",
     "core:svaramanas_is_loudness_matched_when_measured"},
    // ---- Svaresa house voicing (docs/SONIC_IDENTITY.md) -----------------------------------------
    {"SM-FOUND-1", 1, Owner::Slow, {M::BassToMidsDb, M::BoomDb, M::BoomDb}, 2, 0.8, 10.0, true, true, false,
     "deep-bass foundation (65 Hz low shelf)", "dB", 0, 3,
     "Lifts only the bass the track lacks against the target balance; yields to SM-BOOM-1 (a measured boom removes it); none on a phone speaker (SV-SPEAKER-1); halved on a crushed master (SM-CRUSH-1)",
     "Deep bass the track was missing was restored", "Slews to zero when the track has enough bass",
     "core:svaresa_house_voicing_is_audible_bounded_and_backs_off_on_evidence"},
    {"SM-BODY-1", 1, Owner::Slow, {M::BoomDb, M::MudDb, M::MudDb}, 2, 0.8, 10.0, true, true, false,
     "warmth bell (180 Hz)", "dB", 0, 0.75,
     "Backs off linearly as SM-BOOM-1 / SM-MUD-1 measure crowding (gone at 4 dB); halved on a crushed master",
     "A little chest and body added to voices and guitars", "Slews to zero as mud or boom appears",
     "core:svaresa_house_voicing_is_audible_bounded_and_backs_off_on_evidence"},
    {"SM-SOFT-1", 1, Owner::Slow, {M::SharpnessRatio, M::BandwidthCutoffHz, M::BandwidthCutoffHz}, 2, 0.8, 10.0, true, true, false,
     "top softening (8.5 kHz high shelf)", "dB", -2.8, 0,
     "Cut-only; stacks with SM-TILT-1 on bright mixes; zero for streams ending below 12 kHz (SM-LOSSY-1)",
     "The top end was sharper than a healthy balance and was softened", "Slews back to the house shelf",
     "core:svaresa_house_voicing_is_audible_bounded_and_backs_off_on_evidence"},
    {"SM-PUNCH-1", 1, Owner::Slow, {M::PlrDb, M::PlrDb, M::PlrDb}, 1, 0.8, 10.0, true, true, false,
     "bass punch (bass-envelope character)", "0..1", 0, 0.12,
     "Shares the +-0.15 automatic bass-character cap; the manual bass shaper stays at full strength",
     "This master is heavily limited, so a little bass punch was restored", "Slews to zero on a dynamic master",
     "core:svaresa_restores_punch_on_limited_masters_and_crushed_masters_get_half_lifts"},
    {"SM-ATMOS-1", 1, Owner::Slow, {M::SideToMidDb, M::MonoLike, M::MonoLike}, 2, 0.8, 10.0, true, true, false,
     "side ambience (stereo space)", "0..1", 0, 0.2,
     "Never on a mono-like source (SM-MONO-1); explicit Space/Instruments values win; capture engine only",
     "The mix is narrower than the target, so a little side ambience was opened", "Slews to zero when the mix is wide enough",
     "core:svaresa_opens_atmosphere_only_on_narrow_stereo_mixes"},
    {"SM-GROUND-1", 1, Owner::Slow, {M::AirDb, M::HarshDb, M::TiltDbPerOct}, 3, 0.8, 10.0, true, true, false,
     "grounding depth (spike restraint and odd-order body)", "0..1", 0, 1,
     "Parallel add-ins after the stereo tuner and before the dynamic EQ; downward-only restraint max 4 dB; true-peak protection stays last",
     "Top-end spikes eased and low-mid weight added so the voice and rhythm keep their body", "Ramps to bit-exact bypass at depth 0",
     "core:svaresa_grounded_voicing_scales_with_measured_top_end_excess_within_bounds"},
    {"SM-HOUSE-1", 1, Owner::Slow, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 0, 0.0, 1e9, false, false, false,
     "house voicing before anything is heard (static foundation, warmth, softness)", "dB", -0.8, 1.0,
     "A listener preference, not a measurement: applies only to Svaresa, scaled by its strength, and the static foundation is dropped on a phone speaker",
     "House voicing: foundation, warmth and a softer top, level-matched", "Off when Svaresa is off",
     "core:svaresa_house_voicing_is_audible_bounded_and_backs_off_on_evidence"},
    {"SM-TASTE-1", 1, Owner::Slow, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 0, 0.0, 1e9, false, false, false,
     "voicing targets from the listener's learned references", "targets", 0, 1,
     "Replaces only the house targets of SM-FOUND-1, SM-SOFT-1, SM-ATMOS-1 and the tilt target; every bound of those rules still applies",
     "Aiming for the sound of the reference tracks you taught me", "Use house voicing forgets it",
     "core:taste_learning_averages_references_and_drives_the_voicing_targets"},
    {"SM-BUDGET-1", 1, Owner::Slow, {M::PeakDbfs, M::PeakDbfs, M::PeakDbfs}, 0, 0.0, 1e9, false, false, false,
     "emphasis budget (sum of positive smart gains)", "dB", 0, 6, "Scales every positive request together",
     "Emphasis scaled to fit the budget", "Restored when requests shrink",
     "core:svaramanas_guardrails_hold_for_every_combination"},
};
}  // namespace

const MetricSpec& spec(Metric m) { return kMetrics[static_cast<int>(m)]; }
const std::vector<Rule>& rules() { return kRules; }
const Rule* find(const std::string& id) {
  for (const auto& r : kRules) if (id == r.id) return &r;
  return nullptr;
}

const char* skipText(Skip s) {
  switch (s) {
    case Skip::None: return "";
    case Skip::MissingInput: return "a required measurement is missing";
    case Skip::Invalid: return "a measurement is not valid";
    case Skip::LowConfidence: return "confidence is too low";
    case Skip::Stale: return "the measurement is too old";
    case Skip::WrongEpoch: return "the measurement belongs to an earlier capture/route/format epoch";
    case Skip::NoNativePcm: return "needs the native audiophile engine's PCM";
    case Skip::ProxyNotAllowed: return "a proxy is not a measurement";
    case Skip::UserOff: return "switched off by the listener";
    case Skip::AutoMasterOff: return "Auto master is off";
  }
  return "";
}

int skipCode(Skip s) { return static_cast<int>(s); }
int ruleIndex(const char* id) {
  for (size_t i = 0; i < kRules.size(); ++i) if (std::strcmp(kRules[i].id, id) == 0) return static_cast<int>(i);
  return -1;
}

Decision admit(const Rule& r, const Measurement* m, int count, const Context& c) {
  if (c.userOff) return {false, Skip::UserOff};
  if (r.nativePcmOnly && !c.nativePcm) return {false, Skip::NoNativePcm};
  if (r.owner != Owner::Context && r.needsAutoMaster && !c.autoMaster) return {false, Skip::AutoMasterOff};
  for (int i = 0; i < r.inputCount; ++i) {
    const Metric want = r.inputs[static_cast<size_t>(i)];
    const Measurement* got = nullptr;
    for (int j = 0; j < count; ++j) if (m[j].id == want) { got = &m[j]; break; }
    if (!got) return {false, Skip::MissingInput};
    if (spec(want).proxy && !r.allowProxy) return {false, Skip::ProxyNotAllowed};
    if (spec(want).pcm && !c.nativePcm) return {false, Skip::NoNativePcm};
    if (!got->valid || !std::isfinite(got->value)) return {false, Skip::Invalid};
    if (!(got->confidence >= r.minConfidence)) return {false, Skip::LowConfidence};
    if (!(got->ageSeconds <= r.maxAgeSeconds)) return {false, Skip::Stale};
    if (r.sameEpoch && got->epoch != c.epoch) return {false, Skip::WrongEpoch};
  }
  return {true, Skip::None};
}

// A non-finite action becomes the rule's neutral value (0 dB; 1.0 for multiplicative "x" rules), kept in range.
double bound(const Rule& r, double v) {
  const double neutral = std::strcmp(r.units, "x") == 0 ? 1.0 : 0.0;
  return std::clamp(std::isfinite(v) ? v : neutral, r.minAction, r.maxAction);
}

std::string validateRegistry() {
  std::set<std::string> ids;
  for (const auto& r : kRules) {
    const std::string id = r.id;
    auto bad = [&](const char* why) { return id + ": " + why; };
    if (!ids.insert(id).second) return bad("duplicate id");
    if (r.version < 1) return bad("version must be >= 1");
    if (r.inputCount < 0 || r.inputCount > 3) return bad("input count out of range");
    if (!std::isfinite(r.minAction) || !std::isfinite(r.maxAction) || r.minAction > r.maxAction) return bad("action bound is not a finite ordered range");
    if (!(r.minConfidence >= 0 && r.minConfidence <= 1)) return bad("confidence out of range");
    if (!(r.maxAgeSeconds > 0)) return bad("maximum age must be positive");
    for (const char* t : {r.parameter, r.units, r.competes, r.reason, r.rollback, r.counterexample})
      if (!t || !*t) return bad("a text field (parameter/units/competes/reason/rollback/counterexample) is empty");
    if (std::strncmp(r.counterexample, "core:", 5) != 0 && std::strncmp(r.counterexample, "kotlin:", 7) != 0) return bad("counterexample must be core:<test> or kotlin:<path>#<method>");
    bool usesPcm = false, usesProxy = false;
    for (int i = 0; i < r.inputCount; ++i) {
      const auto& s = spec(r.inputs[static_cast<size_t>(i)]);
      usesPcm = usesPcm || s.pcm;
      usesProxy = usesProxy || s.proxy;
    }
    if (usesPcm && !r.nativePcmOnly) return bad("reads PCM metrics but is not marked native-only");
    if (r.owner == Owner::Context && usesPcm) return bad("a context rule must not read PCM");
    if (r.owner == Owner::Context && r.nativePcmOnly) return bad("a context rule works on every engine");
    if (usesProxy && !r.allowProxy) return bad("reads a proxy without declaring allowProxy");
    if (r.allowProxy && !(r.maxAction - r.minAction > 0 && r.maxAction - r.minAction <= 6.0)) return bad("a proxy-driven rule needs a hard cap of at most 6 dB");
    if (r.owner == Owner::Fast && r.inputCount > 0 && r.maxAgeSeconds > 1.0) return bad("a fast rule may not accept evidence older than 1 s");
    if (r.owner == Owner::Slow && r.inputCount > 0 && r.maxAgeSeconds > 30.0) return bad("a slow rule may not accept evidence older than 30 s");
    if (r.owner != Owner::Context && r.inputCount > 0 && !r.sameEpoch) return bad("PCM-driven rules must require the same epoch");
  }
  return "";
}

namespace {
void jsonString(std::string& out, const char* s) {
  out += '"';
  for (; *s; ++s) {
    const unsigned char ch = static_cast<unsigned char>(*s);
    if (ch == '"' || ch == '\\') { out += '\\'; out += static_cast<char>(ch); }
    else if (ch < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", ch); out += b; }
    else out += static_cast<char>(ch);
  }
  out += '"';
}
void jsonNumber(std::string& out, double v) {
  char b[40];
  std::snprintf(b, sizeof b, "%.6g", std::isfinite(v) ? v : 0.0);
  out += b;
}
}  // namespace

std::string rulesJson() {
  std::string out = "[";
  bool first = true;
  for (const auto& r : kRules) {
    if (!first) out += ",";
    first = false;
    out += "{\"id\":"; jsonString(out, r.id);
    out += ",\"version\":" + std::to_string(r.version);
    out += ",\"owner\":"; jsonString(out, r.owner == Owner::Fast ? "fast" : r.owner == Owner::Slow ? "slow" : "context");
    out += ",\"inputs\":[";
    for (int i = 0; i < r.inputCount; ++i) {
      const auto& s = spec(r.inputs[static_cast<size_t>(i)]);
      if (i) out += ",";
      out += "{\"name\":"; jsonString(out, s.name);
      out += ",\"units\":"; jsonString(out, s.units);
      out += std::string(",\"proxy\":") + (s.proxy ? "true" : "false") + ",\"pcm\":" + (s.pcm ? "true" : "false") + "}";
    }
    out += "],\"minConfidence\":"; jsonNumber(out, r.minConfidence);
    out += ",\"maxAgeSeconds\":"; jsonNumber(out, r.inputCount ? r.maxAgeSeconds : 0.0);
    out += std::string(",\"sameEpoch\":") + (r.sameEpoch ? "true" : "false") + ",\"nativeOnly\":" + (r.nativePcmOnly ? "true" : "false");
    out += ",\"needsAutoMaster\":"; out += r.needsAutoMaster ? "true" : "false";
    out += ",\"parameter\":"; jsonString(out, r.parameter);
    out += ",\"units\":"; jsonString(out, r.units);
    out += ",\"min\":"; jsonNumber(out, r.minAction);
    out += ",\"max\":"; jsonNumber(out, r.maxAction);
    out += ",\"competes\":"; jsonString(out, r.competes);
    out += ",\"reason\":"; jsonString(out, r.reason);
    out += ",\"rollback\":"; jsonString(out, r.rollback);
    out += ",\"counterexample\":"; jsonString(out, r.counterexample);
    out += "}";
  }
  return out + "]";
}

Effective resolveOwnership(Ownership chosen, double manual, double autoValue, bool autoMasterOn, bool evidenceAdmitted) {
  const double saved = std::isfinite(manual) ? manual : 0.0;
  switch (chosen) {
    case Ownership::Off: return {Ownership::Off, 0.0};
    case Ownership::Manual: return {Ownership::Manual, saved};
    case Ownership::Auto:
      if (autoMasterOn && evidenceAdmitted && std::isfinite(autoValue)) return {Ownership::Auto, std::max(saved, autoValue)};
      return {Ownership::Manual, saved};  // Auto is inactive: the saved value stands untouched
  }
  return {Ownership::Off, 0.0};
}

double headroomScale(const double* req, int n, double ceilingDb) {
  double sum = 0;
  for (int i = 0; i < n; ++i) if (std::isfinite(req[i]) && req[i] > 0) sum += req[i];
  return sum > ceilingDb && sum > 0 ? std::max(0.0, ceilingDb) / sum : 1.0;
}

}  // namespace eqcore::policy

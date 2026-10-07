#pragma once
// Executable rule registry for Svaresa (fast, ~250 ms) and Svaramanas (slow, ~3 s) -- AQ-04.
//
// "Mastering knowledge" is not prose here: every adaptive action is a versioned Rule with the inputs it
// may read (units, validity, confidence, age, epoch), the bounded action it may take, who owns it, what
// else competes for the same range, the reason shown to the listener, how it rolls back, and a named
// counterexample test that tries to break it. validateRegistry() and the evidence gate admit() are
// ordinary code with tests; nothing in a rule's text is trusted unless a test checks the behaviour.
//
// Principles encoded as checks, not slogans:
//   * Unknown or stale evidence means skip (admit() returns a reason, never a guess).
//   * A proxy (volume, clock, route hint) is never treated as a measurement; a rule may use one only if it
//     declares allowProxy and a hard cap. Android volume is not calibrated SPL; inferred bandwidth is not a
//     codec label.
//   * Native-PCM rules cannot run on the opaque system-effects route.
//   * Ownership is explicit Off / Manual / Auto; Auto never lowers or overwrites the saved manual value.
//   * One headroom ledger scales the sum of positive requests.
#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace eqcore::policy {

enum class Owner { Fast, Slow, Context };  // Svaresa ~250 ms on PCM; Svaramanas ~3 s on PCM; context brain (no PCM)

enum class Metric : int {
  PeakDbfs, LoudnessLufs, PlrDb, ClipsPerSecond, Correlation, SideToMidDb, MonoLike, BandwidthCutoffHz,
  TiltDbPerOct, MudDb, BoomDb, HarshDb, AirDb, BassNote, LanePromDb, ResidualCoherence, SideMidPower,
  VolumeProxy, RouteHint, ClockMinutes, kCount
};

struct MetricSpec {
  Metric id;
  const char* name;
  const char* units;
  bool pcm;    // measured from the captured PCM (needs the native engine)
  bool proxy;  // a hint about the situation, not a measurement of the audio
};
const MetricSpec& spec(Metric m);

struct Measurement {
  Metric id = Metric::PeakDbfs;
  double value = 0;
  bool valid = false;
  double confidence = 0;       // 0..1
  uint64_t epoch = 0;          // capture/route/format/parameter epoch the window belongs to
  double ageSeconds = 1e9;
};

struct Rule {
  const char* id;
  int version;
  Owner owner;
  std::array<Metric, 3> inputs;
  int inputCount;
  double minConfidence;        // each input
  double maxAgeSeconds;
  bool sameEpoch;
  bool nativePcmOnly;
  bool allowProxy;             // may read proxy metrics (then the cap below is mandatory and tested)
  const char* parameter;       // what it moves
  const char* units;
  double minAction, maxAction; // hard bound on the action
  const char* competes;        // other processors on the same range and who yields
  const char* reason;          // what the listener is told
  const char* rollback;        // how it unwinds
  const char* counterexample;  // "core:<test name>" or "kotlin:<path under test/java/app/svan>#<method>"
  bool needsAutoMaster = true; // false for protection that must work whatever the master mode
};

const std::vector<Rule>& rules();
const Rule* find(const std::string& id);

enum class Skip { None, MissingInput, Invalid, LowConfidence, Stale, WrongEpoch, NoNativePcm, ProxyNotAllowed, UserOff, AutoMasterOff };
const char* skipText(Skip s);
int skipCode(Skip s);                  // stable integer for JNI (the enum ordinal)
int ruleIndex(const char* id);         // position in rules() / the JSON array, -1 if unknown

struct Context {
  uint64_t epoch = 0;
  bool nativePcm = false;   // false on the system-effects route
  bool autoMaster = false;
  bool userOff = false;
};
struct Decision {
  bool admitted;
  Skip skip;
};

// Evidence gate. `m` must contain a measurement for every input of the rule.
Decision admit(const Rule& r, const Measurement* m, int count, const Context& c);
// Clamp an action to the rule's hard bound (non-finite becomes 0).
double bound(const Rule& r, double value);

// Returns "" when the registry is internally consistent, else the first problem.
std::string validateRegistry();

enum class Ownership { Off, Manual, Auto };
struct Effective {
  Ownership who;
  double value;
};
// Off wins. Manual = the saved value. Auto = max(saved, autoValue) only while Auto master is on and the
// evidence is admitted; otherwise the saved manual value stands (never overwritten, never lowered).
Effective resolveOwnership(Ownership chosen, double manual, double autoValue, bool autoMasterOn, bool evidenceAdmitted);

// The registry as JSON (for a read-only "How Svaresa decides" screen). Strings are escaped; numbers are finite.
std::string rulesJson();

// One headroom authority: scale factor (<= 1) so the positive requests (dB) sum to at most ceilingDb.
double headroomScale(const double* positiveRequestsDb, int n, double ceilingDb);

}  // namespace eqcore::policy

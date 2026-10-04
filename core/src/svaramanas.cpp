#include "eqcore/svaramanas.h"

#include <algorithm>
#include <cmath>

namespace eqcore::svaramanas {

namespace {

constexpr double kPi = 3.14159265358979323846;

struct Shape {
  FilterType type;
  double freqHz, gainDb, q;
};

struct Sound {
  std::vector<Shape> bands;
  double bassCharacter = 0.0;
  StereoTunerParams stereo{};
};

struct CategoryDef {
  uint32_t bit;
  double lo, hi;  // main region; 0,0 = stereo only (never conflicts)
  Sound sound;
};

// What each category asks for. Gains are at strength 1 and go through the budget.
const std::vector<CategoryDef>& categoryDefs() {
  static const std::vector<CategoryDef> defs = {
      {kVocals, 1500, 4000,
       {{{FilterType::Peak, 3000, 2.0, 1.0}, {FilterType::Peak, 250, -0.6, 1.0}}, 0.0, {0.4, 0.0, 0.2, 0.0, 0.0}}},
      {kStrings, 300, 700,
       {{{FilterType::Peak, 450, 1.0, 0.8}, {FilterType::Peak, 7000, 1.2, 0.9}}, 0.0, {0.0, 0.0, 0.0, 0.2, 0.6}}},
      {kPiano, 120, 300,
       {{{FilterType::Peak, 200, 1.2, 0.9}, {FilterType::Peak, 4500, 1.0, 1.2}}, 0.0, {}}},
      {kGuitars, 1800, 3000,
       {{{FilterType::Peak, 110, 0.8, 1.0}, {FilterType::Peak, 2400, 1.5, 1.0}, {FilterType::Peak, 8000, 0.8, 1.0}},
        0.0, {0.0, 0.0, 0.0, 0.0, 0.2}}},
      {kDrums, 4500, 6500,
       {{{FilterType::Peak, 60, 1.5, 1.2}, {FilterType::Peak, 5500, 1.5, 1.2}}, 0.3, {}}},
      {kBass, 45, 150,
       {{{FilterType::LowShelf, 90, 2.0, 0.71}, {FilterType::Peak, 800, 0.8, 1.0}}, 0.0, {}}},
      {kBrass, 700, 1400,
       {{{FilterType::Peak, 1000, 1.5, 0.9}, {FilterType::Peak, 3200, 0.8, 1.2}}, 0.0, {}}},
      {kSynth, 9000, 14000,
       {{{FilterType::LowShelf, 45, 1.2, 0.71}, {FilterType::HighShelf, 11000, 1.5, 0.71}}, 0.0, {}}},
      {kSpace, 0, 0,
       {{{FilterType::HighShelf, 13000, 0.6, 0.71}}, 0.0, {0.0, 0.0, 0.0, 0.5, 0.3}}},
  };
  return defs;
}

const CategoryDef* defFor(uint32_t bit) {
  for (const auto& d : categoryDefs())
    if (d.bit == bit) return &d;
  return nullptr;
}

Sound feelSound(Feel f) {
  switch (f) {
    case Feel::Warm:
      return {{{FilterType::LowShelf, 200, 1.5, 0.71}, {FilterType::HighShelf, 7000, -1.0, 0.71}}, 0.0, {}};
    case Feel::Bright:
      return {{{FilterType::Peak, 250, -0.5, 1.0}, {FilterType::HighShelf, 6000, 1.5, 0.71}}, 0.0, {}};
    case Feel::Punchy:
      return {{{FilterType::Peak, 70, 1.5, 1.1}, {FilterType::Peak, 350, -1.0, 1.0}}, 0.4, {}};
    case Feel::Spacious:
      return {{{FilterType::Peak, 300, -0.5, 1.0}, {FilterType::HighShelf, 12000, 1.0, 0.71}}, 0.0,
              {0.0, 0.0, 0.0, 0.5, 0.0}};
    case Feel::Intimate:
      return {{{FilterType::Peak, 1500, 0.8, 0.7}, {FilterType::HighShelf, 9000, -0.5, 0.71}}, 0.0,
              {0.5, 0.0, 0.0, -0.2, 0.0}};
    case Feel::Balanced:
    default:
      return {{{FilterType::Peak, 250, 0.0, 1.0}, {FilterType::HighShelf, 8000, 0.0, 0.71}}, 0.0, {}};
  }
}

std::vector<uint32_t> pickOrder(const Request& r) {
  std::vector<uint32_t> out;
  for (uint32_t b : r.order)
    if ((r.categories & b) && b && !(b & (b - 1)) && std::find(out.begin(), out.end(), b) == out.end())
      out.push_back(b);
  for (int i = 0; i < kCategoryCount; ++i) {
    const uint32_t b = 1u << i;
    if ((r.categories & b) && std::find(out.begin(), out.end(), b) == out.end()) out.push_back(b);
  }
  return out;
}

bool overlaps(const CategoryDef& a, const CategoryDef& b) {
  if (a.hi <= 0 || b.hi <= 0) return false;
  return a.lo < b.hi && b.lo < a.hi;
}

BiquadCoeffs kShelf(double fs) {
  const double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
  const double k = std::tan(kPi * f0 / fs), vh = std::pow(10.0, g / 20.0), vb = std::pow(vh, 0.4996667741545416);
  const double a0 = 1.0 + k / q + k * k;
  return {(vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
          2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0};
}
BiquadCoeffs kHigh(double fs) {
  const double f0 = 38.13547087602444, q = 0.5003270373238773;
  const double k = std::tan(kPi * f0 / fs), a0 = 1.0 + k / q + k * k;
  return {1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0};
}

void addNote(Plan& p, int n) {
  if (std::find(p.notes.begin(), p.notes.end(), n) == p.notes.end()) p.notes.push_back(n);
}

double clampStereo(double v, double lo) { return std::clamp(v, lo, 1.0); }

}  // namespace

CategoryCheck checkCategories(const Request& r) {
  CategoryCheck c;
  const auto order = pickOrder(r);
  std::vector<const CategoryDef*> kept;
  for (size_t i = 0; i < order.size(); ++i) {
    const CategoryDef* d = defFor(order[i]);
    if (!d) continue;
    if (kept.size() < 3) {
      kept.push_back(d);
      c.accepted |= d->bit;
      continue;
    }
    bool clash = false;
    if (kept.size() < static_cast<size_t>(kMaxCategories)) {
      for (const auto* k : kept)
        if (overlaps(*d, *k)) {
          clash = true;
          c.conflictWith |= k->bit;
        }
    }
    if (kept.size() >= static_cast<size_t>(kMaxCategories) || clash) {
      c.rejected |= d->bit;
    } else {
      kept.push_back(d);
      c.accepted |= d->bit;
    }
  }
  return c;
}

double predictedLoudnessDeltaDb(const std::vector<BandParams>& bands, const double* spectrumDb, double fs) {
  const BiquadCoeffs ks = kShelf(fs), kh = kHigh(fs);
  std::vector<BiquadCoeffs> cs;
  cs.reserve(bands.size());
  for (const auto& b : bands)
    if (!isIdentityBand(b)) cs.push_back(designBiquad(b, fs));
  double num = 0.0, den = 0.0;
  for (int i = 0; i < SourceFeatures::kBands; ++i) {
    const double fc = SourceFeatures::bandCentreHz(i);
    if (fc >= 0.49 * fs) break;
    const double p = spectrumDb ? std::pow(10.0, spectrumDb[i] / 10.0) : 1.0;  // pink: equal per third octave
    const double kdb = magnitudeDb(ks, fc, fs) + magnitudeDb(kh, fc, fs);
    double hdb = 0.0;
    for (const auto& c : cs) hdb += magnitudeDb(c, fc, fs);
    const double w = p * std::pow(10.0, kdb / 10.0);
    den += w;
    num += w * std::pow(10.0, hdb / 10.0);
  }
  return den > 0 ? 10.0 * std::log10(num / den) : 0.0;
}

Plan plan(const Request& r, const SourceFeatures* features) {
  Plan p;
  const double strength = std::clamp(r.strength, 0.0, 1.5);
  const bool heard = features && features->valid;
  if (!heard) addNote(p, kNoteListening);

  // ---- 2. the listener's request: feel + accepted categories -----------------
  p.categories = checkCategories(r);
  if (p.categories.rejected) addNote(p, kNoteConflictDropped);
  struct Slot {
    BandParams band;
    bool request;  // part of the listener's request (budgeted)
  };
  std::vector<Slot> slots;
  StereoTunerParams st{};
  double bassChar = 0.0;

  const Sound feel = feelSound(r.feel);
  if (r.feel != Feel::Balanced) addNote(p, kNoteFeel);
  for (const auto& s : feel.bands) slots.push_back({{s.type, s.freqHz, s.gainDb * strength, s.q, true}, true});
  bassChar += feel.bassCharacter * strength;
  st.intimacy += feel.stereo.intimacy;
  st.space += feel.stereo.space;
  st.instruments += feel.stereo.instruments;

  std::vector<double> acceptedFreqs;  // positive request bands already placed by earlier picks
  for (uint32_t bit : pickOrder(r)) {
    if (!(p.categories.accepted & bit)) continue;
    const CategoryDef* d = defFor(bit);
    addNote(p, kNoteCategories);
    std::vector<double> mine;
    for (const auto& s : d->sound.bands) {
      double g = s.gainDb * strength;
      // Overlap with an earlier pick's lift (within half an octave): this one yields.
      if (g > 0) {
        for (double f : acceptedFreqs)
          if (std::fabs(std::log2(s.freqHz / f)) < 0.5) {
            g *= 0.6;
            addNote(p, kNoteOverlapSoftened);
            break;
          }
        mine.push_back(s.freqHz);
      }
      slots.push_back({{s.type, s.freqHz, g, s.q, true}, true});
    }
    acceptedFreqs.insert(acceptedFreqs.end(), mine.begin(), mine.end());
    bassChar += d->sound.bassCharacter * strength;
    st.intimacy += d->sound.stereo.intimacy;
    st.smoothness += d->sound.stereo.smoothness;
    st.space += d->sound.stereo.space;
    st.instruments += d->sound.stereo.instruments;
  }

  // ---- 1. budget + per-band cap on the request ---------------------------------
  double positive = 0.0;
  for (auto& s : slots) {
    s.band.gainDb = std::clamp(s.band.gainDb, -kMaxBandDb, kMaxBandDb);
    if (s.band.gainDb > 0) positive += s.band.gainDb;
  }
  if (positive > kEmphasisBudgetDb) {
    const double k = kEmphasisBudgetDb / positive;
    for (auto& s : slots)
      if (s.band.gainDb > 0) s.band.gainDb *= k;
    addNote(p, kNoteBudget);
  }

  // ---- 3. clarity corrections from what was heard (fixed skeleton) -------------
  const size_t boomIdx = slots.size();
  slots.push_back({{FilterType::Peak, 90, 0.0, 1.0, true}, false});
  const size_t mudIdx = slots.size();
  slots.push_back({{FilterType::Peak, 300, 0.0, 1.0, true}, false});
  const size_t harshIdx = slots.size();
  slots.push_back({{FilterType::Peak, 3500, 0.0, 1.2, true}, false});
  const size_t airIdx = slots.size();
  slots.push_back({{FilterType::HighShelf, 11000, 0.0, 0.71, true}, false});

  if (heard) {
    const double cs = std::min(strength, 1.0);
    auto excess = [&](double x, double thr) { return x > thr ? std::min(kMaxCorrectionDb, 0.6 * (x - thr)) * cs : 0.0; };
    const double boom = excess(features->boomDb, 3.0);
    const double mud = excess(features->mudDb, 2.0);
    const double harsh = excess(features->harshDb, 2.0);
    if (boom > 0) { slots[boomIdx].band.gainDb = -boom; addNote(p, kNoteBoom); }
    if (mud > 0) { slots[mudIdx].band.gainDb = -mud; addNote(p, kNoteMud); }
    if (harsh > 0) {
      slots[harshIdx].band.gainDb = -harsh;
      addNote(p, kNoteHarsh);
      // Respect: don't push presence into a region that is already shrill.
      for (size_t i = 0; i < harshIdx; ++i) {
        auto& b = slots[i].band;
        if (b.gainDb > 0 && b.freqHz >= 2000 && b.freqHz <= 6000) b.gainDb *= 0.5;
      }
    }
    const bool fullBand = features->cutoffHz >= 17500.0;
    if (features->airDb < -4.0 && fullBand && r.feel != Feel::Warm) {
      slots[airIdx].band.gainDb = std::min(1.5, 0.3 * (-features->airDb - 3.0)) * cs;
      addNote(p, kNoteDull);
    }
    // 4. respect the music.
    if (features->cutoffHz > 0 && features->cutoffHz < 17500.0) {
      bool changed = false;
      for (auto& s : slots)
        if (s.band.gainDb > 0 && s.band.freqHz >= 0.7 * features->cutoffHz) {
          s.band.gainDb = 0.0;
          changed = true;
        }
      if (changed) addNote(p, kNoteLossy);
    }
    if (features->plrDb > 0 && (features->plrDb < 8.0 || features->clipsPerSecond > 1.0)) {
      for (auto& s : slots)
        if (s.band.gainDb > 0) s.band.gainDb *= 0.5;
      addNote(p, kNoteCrushedMaster);
    }
    if (features->monoLike && (st.space != 0 || st.instruments != 0)) {
      st.space = 0;
      st.instruments = 0;
      addNote(p, kNoteMono);
    }
  }

  for (auto& s : slots) {
    s.band.gainDb = std::clamp(s.band.gainDb, -kMaxBandDb, kMaxBandDb);
    p.bands.push_back(s.band);
  }
  p.bassCharacter = std::clamp(bassChar, -1.0, 1.0);
  if (r.stereoEngine) {
    p.stereo.intimacy = clampStereo(st.intimacy, 0.0);
    p.stereo.smoothness = clampStereo(st.smoothness, 0.0);
    p.stereo.warmth = 0.0;
    p.stereo.space = clampStereo(st.space, -1.0);
    p.stereo.instruments = clampStereo(st.instruments, 0.0);
  }

  // ---- 1. loudness match: never win by being louder ------------------------------
  p.predictedDeltaDb = predictedLoudnessDeltaDb(p.bands, heard ? features->bandDb.data() : nullptr);
  p.preampDb = std::clamp(-p.predictedDeltaDb, -8.0, 1.5);
  if (std::fabs(p.predictedDeltaDb) > 0.05) addNote(p, kNoteLoudnessMatched);
  return p;
}

}  // namespace eqcore::svaramanas

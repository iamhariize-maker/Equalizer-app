#include "eqcore/bass_texture.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kQ = 0.7071067811865476;  // Butterworth section; two sections make a 4th-order (LR4) split
constexpr double kSubHz = 150.0;           // generator band: bass fundamentals and their first harmonics
constexpr double kRumbleHz = 30.0;         // removes intermodulation products (and the rectifier's DC) below the bass
constexpr double kLevelRef = 0.25;         // band level at which the drive reaches its maximum (-12 dBFS)
constexpr double kDriveMin = 0.5;          // quiet notes: almost linear
constexpr double kDriveMax = 4.5;          // loud notes: about -25 dBc third harmonic at full depth
constexpr double kEvenMax = 0.18;          // rectifier gain at the reference level: about -23 dBc second harmonic
constexpr int kDescribeSteps = 512;        // sampling points of the describing-function integral
// Dimension: harmonics above kSpreadHz get the all-pass phase difference; kAllpassHz sets how much (about 0.2 ms).
constexpr double kSpreadHz = 200.0;
constexpr double kAllpassHz = 1500.0;
// Attack: pick/slap band and the onset threshold (fast over slow sub-band power).
constexpr double kPickHz = 1200.0, kPickQ = 0.5;
constexpr double kAttackMaxDb = 2.0;
constexpr double kOnsetFromDb = 4.0, kOnsetSpanDb = 6.0;  // a steady sine's power ripple stays under 3 dB
constexpr double kOnsetFloor = 1e-6;                      // sub-band power floor, about -60 dBFS
// Sustain: lift starts 2 dB under the note's recent peak, 0.5 dB per dB of decay, at most 3 dB.
constexpr double kSustainDeadbandDb = 2.0, kSustainMaxDb = 3.0;
constexpr double kSustainFloor = 1e-6;  // sub-band power floor, about -60 dBFS
constexpr double kEps = 1e-30;
constexpr double kProjectFloor = 1e-10;  // sub-band power floor for the in-phase projection, about -100 dBFS

double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }

double drive(double level) {
  return kDriveMin + (kDriveMax - kDriveMin) * std::min(level / kLevelRef, 1.0);
}

// Fundamental Fourier coefficient of f(x) = tanh(k x) / k for x = amplitude * sin(theta), divided by amplitude.
double describe(double amplitude, double k) {
  if (!(amplitude > 1e-9)) return 1.0;
  double sum = 0.0;
  for (int i = 0; i < kDescribeSteps; ++i) {
    const double theta = 2.0 * kPi * (i + 0.5) / kDescribeSteps;
    const double s = std::sin(theta);
    sum += std::tanh(k * amplitude * s) / k * s;
  }
  return (2.0 * sum / kDescribeSteps) / amplitude;
}

// The level follower (5 ms up, 150 ms down on |sub|) settles a little under a steady note's amplitude. Its ratio is
// the same at every level (the follower is homogeneous); measured on a 60 Hz sine, it lets the table remove the
// shaper's fundamental at the note's actual amplitude, not at the follower's reading.
double followerRatio() {
  const double fs = 48000.0;
  const double up = std::exp(-1.0 / (5e-3 * fs)), down = std::exp(-1.0 / (150e-3 * fs));
  double level = 0.0, sum = 0.0;
  const int settle = static_cast<int>(fs), measure = 800;  // 1 s to settle, then one 60 Hz cycle
  for (int i = 0; i < settle + measure; ++i) {
    const double m = std::fabs(std::sin(2.0 * kPi * 60.0 * i / fs));
    level = m > level ? up * level + (1.0 - up) * m : down * level + (1.0 - down) * m;
    if (i >= settle) sum += level;
  }
  return sum / measure;
}

}  // namespace

double BassTexture::fundamentalGain(double level) {
  if (!(level > 1e-9)) return 1.0;
  // Fundamental Fourier coefficient of f(x) = tanh(k x) / k for x = level * sin(theta), divided by level.
  const double k = drive(level);
  double sum = 0.0;
  for (int i = 0; i < kDescribeSteps; ++i) {
    const double theta = 2.0 * kPi * (i + 0.5) / kDescribeSteps;
    const double s = std::sin(theta);
    sum += std::tanh(k * level * s) / k * s;
  }
  return (2.0 * sum / kDescribeSteps) / level;
}

const std::array<double, BassTexture::kTableSize>& BassTexture::table() {
  static const std::array<double, kTableSize> values = [] {
    std::array<double, kTableSize> t{};
    // Indexed by the follower's reading; evaluated at the amplitude that reading stands for, with the drive it sets.
    const double ratio = followerRatio();
    for (int j = 0; j < kTableSize; ++j) {
      const double level = kTableMax * j / (kTableSize - 1);
      t[static_cast<size_t>(j)] = describe(level / ratio, drive(level));
    }
    return t;
  }();
  return values;
}

BassTexture::BassTexture(double sampleRate) {
  table();  // build the table here, never on the audio thread
  const auto low = designBiquad({FilterType::LowPass, kSubHz, 0.0, kQ, true}, sampleRate);
  const auto rumble = designBiquad({FilterType::HighPass, kRumbleHz, 0.0, kQ, true}, sampleRate);
  for (int i = 0; i < 2; ++i) {
    lowLp_[i].c = low;
    highPass_[i].c = rumble;
  }
  spreadHp_.c = designBiquad({FilterType::HighPass, kSpreadHz, 0.0, kQ, true}, sampleRate);
  const double t = std::tan(kPi * kAllpassHz / sampleRate);
  apCoeff_ = (t - 1.0) / (t + 1.0);
  pickBand_.c = designBiquad({FilterType::BandPass, kPickHz, 0.0, kPickQ, true}, sampleRate);
  aRamp_ = coeff(20, sampleRate);
  aUp_ = coeff(5, sampleRate);
  aDown_ = coeff(150, sampleRate);
  aOnsetFast_ = coeff(1, sampleRate);
  aOnsetSlow_ = coeff(50, sampleRate);
  aGateUp_ = coeff(1, sampleRate);
  aGateDown_ = coeff(10, sampleRate);
  aEnvUp_ = aEnvDown_ = coeff(30, sampleRate);  // symmetric power smoothing: a steady note gives a flat envelope
  aPeakDown_ = coeff(400, sampleRate);
  aLiftUp_ = coeff(20, sampleRate);
  aProject_ = coeff(50, sampleRate);
}

void BassTexture::setControl(Control c, double v) {
  target_[c].store(std::isfinite(v) ? std::clamp(v, 0.0, 1.0) : 0.0, std::memory_order_relaxed);
}
void BassTexture::setDepth(double depth) { setControl(kDepth, depth); }
void BassTexture::setEvenMix(double evenMix) { setControl(kEven, evenMix); }
void BassTexture::setAttack(double attack) { setControl(kAttack, attack); }
void BassTexture::setSpread(double spread) { setControl(kSpread, spread); }
void BassTexture::setSustain(double sustain) { setControl(kSustain, sustain); }

void BassTexture::reset() {
  for (int i = 0; i < 2; ++i) {
    lowLp_[i].z1 = lowLp_[i].z2 = 0.0;
    highPass_[i].z1 = highPass_[i].z2 = 0.0;
  }
  spreadHp_.z1 = spreadHp_.z2 = 0.0;
  pickBand_.z1 = pickBand_.z2 = 0.0;
  apX1_ = apY1_ = 0.0;
  level_ = 0.0;
  onsetFast_ = onsetSlow_ = gate_ = 0.0;
  env_ = peak_ = 0.0;
  lift_ = 1.0;
  attackLiftDb_.store(0.0, std::memory_order_relaxed);
  sustainLiftDb_.store(0.0, std::memory_order_relaxed);
  corr_ = subPower_ = 0.0;
  for (size_t c = 0; c < kControls; ++c) value_[c] = target_[c].load(std::memory_order_relaxed);
}

void BassTexture::process(double* left, double* right, int frames) {
  const auto& t = table();
  std::array<double, kControls> target{};
  for (size_t c = 0; c < kControls; ++c) target[c] = target_[c].load(std::memory_order_relaxed);
  const double tableStep = kTableMax / (kTableSize - 1);
  double lastPickGain = 0.0;
  for (int i = 0; i < frames; ++i) {
    for (size_t c = 0; c < kControls; ++c) {
      double& v = value_[c];
      v = target[c] + aRamp_ * (v - target[c]);
      if (target[c] == 0.0 && v < 1e-7) v = 0.0;  // settle exactly, so each bypass is bit-exact
    }
    const double depth = value_[kDepth], even = value_[kEven], attack = value_[kAttack];
    const double spread = value_[kSpread], sustain = value_[kSustain];
    const double mid = right ? 0.5 * (left[i] + right[i]) : left[i];
    const double sub = lowLp_[1].run(lowLp_[0].run(mid));
    const double magnitude = std::fabs(sub);
    const double power = sub * sub;
    level_ = magnitude > level_ ? aUp_ * level_ + (1.0 - aUp_) * magnitude
                                : aDown_ * level_ + (1.0 - aDown_) * magnitude;

    // Texture: odd harmonics from the shaper (its own fundamental removed), even ones from the rectifier.
    const double x = std::min(level_ / tableStep, static_cast<double>(kTableSize - 1) - 1e-9);
    const int j = static_cast<int>(x);
    const double frac = x - j;
    const double fundamental = t[static_cast<size_t>(j)] * (1.0 - frac) + t[static_cast<size_t>(j + 1)] * frac;
    const double k = drive(level_);
    const double shaped = std::tanh(k * sub) / k;
    const double rectified = even * kEvenMax * std::min(level_ / kLevelRef, 1.0) * magnitude;
    // What is left of the generated signal in phase with the bass band itself (intermodulation of a note's own
    // partials lands on its fundamental) is projected out over 50 ms, so the notes keep their level and only new
    // harmonics are added.
    const double raw = shaped - fundamental * sub + rectified;
    corr_ = aProject_ * corr_ + (1.0 - aProject_) * raw * sub;
    subPower_ = aProject_ * subPower_ + (1.0 - aProject_) * power;
    const double projection = subPower_ > kProjectFloor ? corr_ / subPower_ : 0.0;
    const double wet = highPass_[1].run(highPass_[0].run(raw - projection * sub));
    const double add = depth * wet;

    // Dimension: the harmonics above 200 Hz, given an all-pass phase difference between the channels (pure side).
    const double hp = spreadHp_.run(add);
    const double ap = apCoeff_ * hp + apX1_ - apCoeff_ * apY1_;
    apX1_ = hp;
    apY1_ = ap;
    const double side = spread * 0.5 * (ap - hp);

    // Attack: a sub-band onset opens a short window that lifts the pick/slap band of the mid.
    onsetFast_ = aOnsetFast_ * onsetFast_ + (1.0 - aOnsetFast_) * power;
    onsetSlow_ = aOnsetSlow_ * onsetSlow_ + (1.0 - aOnsetSlow_) * power;
    double onset = 0.0;
    if (attack > 0.0 && onsetFast_ > kOnsetFloor) {  // the logarithm only when the control is on
      const double onsetDb = 10.0 * std::log10((onsetFast_ + kEps) / (onsetSlow_ + kEps));
      onset = std::clamp((onsetDb - kOnsetFromDb) / kOnsetSpanDb, 0.0, 1.0);
    }
    gate_ = onset > gate_ ? aGateUp_ * gate_ + (1.0 - aGateUp_) * onset : aGateDown_ * gate_ + (1.0 - aGateDown_) * onset;
    const double pick = pickBand_.run(mid);
    const double pickGain = attack > 0.0 ? std::pow(10.0, kAttackMaxDb * attack * gate_ / 20.0) - 1.0 : 0.0;

    // Sustain: lift the sub band while the note decays below its recent peak, never above that peak.
    env_ = aEnvDown_ * env_ + (1.0 - aEnvDown_) * power;
    peak_ = std::max(env_, aPeakDown_ * peak_);
    double liftTarget = 1.0;
    if (sustain > 0.0 && env_ > kSustainFloor) {
      const double decayDb = 10.0 * std::log10((peak_ + kEps) / (env_ + kEps));
      const double liftDb = std::clamp(0.5 * (decayDb - kSustainDeadbandDb), 0.0, kSustainMaxDb * sustain);
      liftTarget = std::pow(10.0, liftDb / 20.0);
    }
    lift_ = liftTarget < lift_ ? liftTarget : aLiftUp_ * lift_ + (1.0 - aLiftUp_) * liftTarget;
    if (sustain == 0.0) lift_ = 1.0;
    const double body = (lift_ - 1.0) * sub + pickGain * pick;
    lastPickGain = pickGain;

    left[i] += add + body + side;
    if (right) right[i] += add + body - side;
  }
  if (frames > 0) {  // two logarithms per block, not per sample
    attackLiftDb_.store(lastPickGain > 0.0 ? 20.0 * std::log10(1.0 + lastPickGain) : 0.0, std::memory_order_relaxed);
    sustainLiftDb_.store(lift_ > 1.0 ? 20.0 * std::log10(lift_) : 0.0, std::memory_order_relaxed);
  }
}

}  // namespace eqcore

#include "eqcore/bass_texture.h"

#include <algorithm>
#include <cmath>

namespace eqcore {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kQ = 0.7071067811865476;  // Butterworth section; two sections make a 4th-order (LR4) split
constexpr double kSubHz = 150.0;           // generator band: bass fundamentals and their first harmonics
constexpr double kRumbleHz = 30.0;         // removes intermodulation products below the bass
constexpr double kLevelRef = 0.25;         // band level at which the drive reaches its maximum (-12 dBFS)
constexpr double kDriveMin = 0.5;          // quiet notes: almost linear
constexpr double kDriveMax = 2.5;          // loud notes: about -27 dBc third harmonic at full depth
constexpr int kDescribeSteps = 512;        // sampling points of the describing-function integral

double coeff(double ms, double fs) { return std::exp(-1.0 / (ms * 1e-3 * fs)); }

double drive(double level) {
  return kDriveMin + (kDriveMax - kDriveMin) * std::min(level / kLevelRef, 1.0);
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
    for (int j = 0; j < kTableSize; ++j) t[static_cast<size_t>(j)] = fundamentalGain(kTableMax * j / (kTableSize - 1));
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
  aDepth_ = coeff(20, sampleRate);
  aUp_ = coeff(5, sampleRate);
  aDown_ = coeff(150, sampleRate);
}

void BassTexture::setDepth(double depth) {
  const double d = std::isfinite(depth) ? std::clamp(depth, 0.0, 1.0) : 0.0;
  target_.store(d, std::memory_order_relaxed);
}

void BassTexture::reset() {
  for (int i = 0; i < 2; ++i) {
    lowLp_[i].z1 = lowLp_[i].z2 = 0.0;
    highPass_[i].z1 = highPass_[i].z2 = 0.0;
  }
  level_ = 0.0;
  depth_ = target_.load(std::memory_order_relaxed);
}

void BassTexture::process(double* left, double* right, int frames) {
  const auto& t = table();
  const double target = target_.load(std::memory_order_relaxed);
  const double tableStep = kTableMax / (kTableSize - 1);
  for (int i = 0; i < frames; ++i) {
    depth_ = target + aDepth_ * (depth_ - target);
    if (target == 0.0 && depth_ < 1e-7) depth_ = 0.0;  // settle exactly, so the bypass is bit-exact
    const double mid = right ? 0.5 * (left[i] + right[i]) : left[i];
    const double sub = lowLp_[1].run(lowLp_[0].run(mid));
    const double magnitude = std::fabs(sub);
    level_ = magnitude > level_ ? aUp_ * level_ + (1.0 - aUp_) * magnitude
                                : aDown_ * level_ + (1.0 - aDown_) * magnitude;
    // Fundamental gain of the shaper at this level (linear interpolation in the table).
    const double x = std::min(level_ / tableStep, static_cast<double>(kTableSize - 1) - 1e-9);
    const int j = static_cast<int>(x);
    const double frac = x - j;
    const double fundamental = t[static_cast<size_t>(j)] * (1.0 - frac) + t[static_cast<size_t>(j + 1)] * frac;
    const double k = drive(level_);
    const double shaped = std::tanh(k * sub) / k;
    const double wet = highPass_[1].run(highPass_[0].run(shaped - fundamental * sub));
    const double add = depth_ * wet;
    left[i] += add;
    if (right) right[i] += add;
  }
}

}  // namespace eqcore

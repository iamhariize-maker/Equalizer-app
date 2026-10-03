#include "eqcore/tuning.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <sstream>

namespace eqcore {

namespace {
constexpr double kFmin = 20.0, kFmax = 20000.0;

double shelfDb(double f, double f0, double gainDb) {
  // Analog 1st-order-ish shelf shape (smooth, monotonic): gain at low f, 0 at high f.
  const double x = std::log2(f / f0);
  return gainDb / (1.0 + std::pow(2.0, 2.0 * x));
}
}  // namespace

double FrCurve::at(double f) const {
  if (hz.empty()) return 0.0;
  if (f <= hz.front()) return db.front();
  if (f >= hz.back()) return db.back();
  const auto it = std::upper_bound(hz.begin(), hz.end(), f);
  const size_t i = static_cast<size_t>(it - hz.begin());
  const double l0 = std::log(hz[i - 1]), l1 = std::log(hz[i]);
  const double t = (std::log(f) - l0) / (l1 - l0);
  return db[i - 1] + t * (db[i] - db[i - 1]);
}

FrCurve parseCurve(const std::string& text) {
  std::vector<std::pair<double, double>> pts;
  std::istringstream lines(text);
  std::string line;
  while (std::getline(lines, line)) {
    for (char& c : line)
      if (c == ',' || c == ';' || c == '\t') c = ' ';
    std::istringstream ss(line);
    std::string a, b;
    if (!(ss >> a >> b)) continue;
    char* e1 = nullptr;
    char* e2 = nullptr;
    const double f = std::strtod(a.c_str(), &e1);
    const double g = std::strtod(b.c_str(), &e2);
    if (e1 == a.c_str() || *e1 != '\0' || e2 == b.c_str() || *e2 != '\0') continue;  // header/comment
    if (!(f > 0) || !std::isfinite(g)) continue;
    pts.emplace_back(f, g);
  }
  std::sort(pts.begin(), pts.end());
  FrCurve c;
  for (const auto& [f, g] : pts) {
    if (!c.hz.empty() && f <= c.hz.back()) continue;  // drop duplicate frequencies
    c.hz.push_back(f);
    c.db.push_back(g);
  }
  if (c.hz.size() < 2) return {};
  return c;
}

FrCurve smooth(const FrCurve& c, double fraction) {
  if (c.empty() || fraction <= 0) return c;
  FrCurve out = c;
  const double half = 0.5 * fraction;  // octaves on each side
  for (size_t i = 0; i < c.hz.size(); ++i) {
    const double lo = c.hz[i] * std::pow(2.0, -half), hi = c.hz[i] * std::pow(2.0, half);
    // Average on an even log grid so dense high-frequency points don't dominate.
    double sum = 0;
    const int n = 16;
    for (int k = 0; k < n; ++k) sum += c.at(lo * std::pow(hi / lo, (k + 0.5) / n));
    out.db[i] = sum / n;
  }
  return out;
}

FrCurve computeCorrection(const FrCurve& measurement, const FrCurve& target, const TuningOptions& opt, int points) {
  FrCurve out;
  if (measurement.empty() || target.empty() || points < 8) return out;
  const FrCurve m = smooth(measurement, 1.0 / 12);
  const FrCurve t = smooth(target, 1.0 / 12);

  // Level alignment over the midrange, where every target and rig agree best.
  double offset = 0;
  int n = 0;
  for (int k = 0; k < 64; ++k) {
    const double f = 300.0 * std::pow(10.0, k / 63.0);  // 300 Hz..3 kHz
    offset += t.at(f) - m.at(f);
    ++n;
  }
  offset /= n;

  FrCurve raw;
  for (int k = 0; k < points; ++k) {
    const double f = kFmin * std::pow(kFmax / kFmin, k / (points - 1.0));
    const double wanted = t.at(f) + shelfDb(f, 105.0, opt.bassDb) + opt.tiltDbPerOct * std::log2(f / 1000.0);
    raw.hz.push_back(f);
    raw.db.push_back(wanted - (m.at(f) + offset));
  }
  // Gentle smoothing everywhere, heavy in the treble: narrow treble peaks are
  // ear-specific, chasing them makes things worse.
  const FrCurve light = smooth(raw, 1.0 / 6);
  const FrCurve heavy = smooth(raw, 1.0 / 2);
  out.hz = raw.hz;
  out.db.resize(raw.db.size());
  for (size_t i = 0; i < raw.hz.size(); ++i) {
    const double f = raw.hz[i];
    double w = 0;  // 0 = light, 1 = heavy; 1-octave crossfade above trebleSmoothFromHz
    if (f > opt.trebleSmoothFromHz) w = std::min(1.0, std::log2(f / opt.trebleSmoothFromHz));
    double g = (1 - w) * light.db[i] + w * heavy.db[i];
    g = std::clamp(g, -opt.maxCutDb, opt.maxBoostDb);
    if (f > opt.fadeFromHz) {
      const double fade = std::clamp(std::log(f / opt.fadeFromHz) / std::log(opt.fadeToHz / opt.fadeFromHz), 0.0, 1.0);
      g *= 1.0 - fade;
    }
    out.db[i] = g;
  }
  return out;
}

DenseFit fitDenseBands(const FrCurve& curve, int bandCount, double fs) {
  DenseFit fit;
  if (curve.empty() || bandCount < 4) return fit;
  const int n = bandCount;
  const double octaves = std::log2(kFmax / kFmin) / (n - 1);
  const double r = std::pow(2.0, octaves);
  // Each bell spans two band spacings: neighbours overlap enough that their
  // sum is smooth between centres (bandwidth = spacing ripples visibly).
  const double r2 = r * r;
  const double q = std::sqrt(r2) / (r2 - 1);
  std::vector<double> centers(n), gains(n);
  for (int i = 0; i < n; ++i) {
    centers[i] = kFmin * std::pow(r, i);
    gains[i] = curve.at(centers[i]);
  }
  auto respAt = [&](double f) {
    double total = 0;
    for (int i = 0; i < n; ++i)
      total += magnitudeDb(designBiquad({FilterType::Peak, centers[i], gains[i], q, true}, fs), f, fs);
    return total;
  };
  // Neighbouring bells overlap, so start from the curve and correct the
  // residual at each centre (damped Jacobi iteration on exact responses).
  // Wider bells couple more strongly, so the damped update needs more passes.
  for (int iter = 0; iter < 80; ++iter) {
    std::vector<double> err(n);
    for (int i = 0; i < n; ++i) err[i] = curve.at(centers[i]) - respAt(centers[i]);
    for (int i = 0; i < n; ++i) gains[i] = std::clamp(gains[i] + 0.4 * err[i], -24.0, 24.0);
  }
  for (int i = 0; i < n; ++i) fit.bands.push_back({FilterType::Peak, centers[i], gains[i], q, true});
  double sq = 0, mx = 0;
  int cnt = 0;
  for (int k = 0; k < 200; ++k) {
    const double f = 30.0 * std::pow(14000.0 / 30.0, k / 199.0);
    const double e = respAt(f) - curve.at(f);
    sq += e * e;
    mx = std::max(mx, std::fabs(e));
    ++cnt;
  }
  fit.rmsErrorDb = std::sqrt(sq / cnt);
  fit.maxErrorDb = mx;
  return fit;
}

}  // namespace eqcore

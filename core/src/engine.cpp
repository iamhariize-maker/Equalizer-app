#include "eqcore/engine.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace eqcore {

EngineConfig EngineConfig::forQuality(QualityMode mode, double sampleRate, int channels,
                                      int outputBits) {
  EngineConfig c;
  c.sampleRate = sampleRate;
  c.channels = channels;
  c.truePeak = true;
  switch (mode) {
    case QualityMode::Efficient:
      c.oversample = 1;
      c.ditherBits = 0;
      break;
    case QualityMode::HighQuality:
      c.oversample = 2;
      c.stopbandDb = 100.0;
      c.ditherBits = outputBits;
      c.ditherMode = DitherMode::Tpdf;
      break;
    case QualityMode::Audiophile:
      c.oversample = 4;
      c.stopbandDb = 120.0;
      c.ditherBits = outputBits;
      c.ditherMode = DitherMode::Tpdf;
      break;
    case QualityMode::Extreme:
      c.oversample = 8;
      c.stopbandDb = 140.0;
      c.ditherBits = outputBits;
      c.ditherMode = DitherMode::ShapedTpdf;
      break;
  }
  return c;
}

namespace {
// -0.1 dBFS: leaves room for the dither's +-1 LSB without reaching full scale.
constexpr double kAgpCeiling = 0.98855;
constexpr double kUnknown = std::numeric_limits<double>::quiet_NaN();
constexpr double kTopBudgetDb = 4.5;  // combined 3-6 kHz reduction the guard may complete, dB
int sanitizeFactor(int f) { return (f == 2 || f == 4 || f == 8) ? f : 1; }
}  // namespace

Engine::Engine(const EngineConfig& cfg)
    : cfg_(cfg),
      autoHeadroom_(cfg.autoHeadroom),
      gainProtection_(cfg.gainProtection),
      eq_(std::max(1, cfg.channels), cfg.sampleRate * sanitizeFactor(cfg.oversample)),
      bass_(cfg.sampleRate, std::max(1, cfg.channels)),
      unmask_(cfg.sampleRate),
      limiter_(cfg.sampleRate,std::max(1,cfg.channels)),
      dynamic_(cfg.sampleRate),
      stereo_(cfg.sampleRate, cfg.spatialResidual && cfg.channels == 2),
      grounding_(cfg.sampleRate),
      texture_(cfg.sampleRate),
      shrill_(cfg.sampleRate),
      analyzer_(cfg.sampleRate, std::clamp(cfg.channels, 1, 2)) {
  cfg_.channels = std::max(1, cfg_.channels);
  cfg_.oversample = sanitizeFactor(cfg_.oversample);
  cfg_.maxBlock = std::max(16, cfg_.maxBlock);
  if(cfg_.lab.block)lab_=std::make_unique<LabEq>(cfg_.lab,cfg_.channels);
  for (int ch = 0; ch < cfg_.channels; ++ch) {
    OversamplerSpec spec;
    spec.factor = cfg_.oversample;
    spec.baseSampleRate = cfg_.sampleRate;
    spec.stopbandDb = cfg_.stopbandDb;
    spec.maxBlock = cfg_.maxBlock;
    os_.push_back(std::make_unique<Oversampler>(spec));
    // Different seeds per channel: correlated dither would image in the centre.
    dither_.emplace_back(cfg_.ditherBits, cfg_.ditherMode, 0x9E3779B97F4A7C15ull + 7919ull * ch);
  }
  outBuf_.assign(static_cast<size_t>(cfg_.maxBlock) * cfg_.channels, 0.0);
  high_.assign(static_cast<size_t>(cfg_.maxBlock) * cfg_.oversample, 0.0);
  gains_.assign(cfg_.maxBlock,1.0);
}

void Engine::setBands(int channel, const std::vector<BandParams>& bands) {
  eq_.setBands(channel, bands);
  updateGain();
}

void Engine::setBandsAllChannels(const std::vector<BandParams>& bands) {
  for (int ch = 0; ch < cfg_.channels; ++ch) eq_.setBands(ch, bands);
  updateGain();
}

void Engine::setPreampDb(double db) {
  if(userPreampDb_.exchange(db)!=db) updateGain();
}

void Engine::setBassCharacter(double character, double crossoverHz) {
  bassCharacter_.store(character);
  bassCrossover_.store(crossoverHz);
}

void Engine::updateGain() {
  double headroom = 0.0;
  if (autoHeadroom_.load()) {
    const double maxHz = std::min(20000.0, 0.49 * cfg_.sampleRate);
    for (int ch = 0; ch < cfg_.channels; ++ch)
      headroom = std::max(headroom, eq_.peakGainDb(ch, 10.0, maxHz));
  }
  const double preamp = userPreampDb_.load();
  // Existing negative preamp already provides headroom (AutoEq presets do this).
  // Only add the attenuation still needed; never subtract the full peak twice.
  gainDb_.store(autoHeadroom_.load() ? preamp - std::max(0.0, headroom + preamp) : preamp);
}

double Engine::responseDb(int channel, double freqHz) const {
  return eq_.responseDb(channel, freqHz) + gainDb_.load();
}

int Engine::latencyFrames() const { return (os_.empty() ? 0 : os_[0]->latencySamples()) + (cfg_.truePeak?limiter_.latencyFrames():0) + stereo_.latencyFrames() + (lab_?lab_->latencyFrames():0); }

void Engine::reset() {
  gainInitialized_=false;gainRampRemaining_=0;
  eq_.reset();
  if(lab_)lab_->reset();
  bass_.reset();unmask_.reset();
  limiter_.reset();dynamic_.reset();
  stereo_.reset();grounding_.reset();texture_.reset();shrill_.reset();
  analyzer_.reset();
  resetGainProtection();
  for (auto& o : os_) o->reset();
  for (auto& d : dither_) d.reset();
}

void Engine::process(const float* in, float* out, int frames) {
  const int C = cfg_.channels;
  // Before processing: `in` may alias `out`. The analyser reads the first two channels.
  if (analysisOn_.load(std::memory_order_relaxed) && C <= 2) {
    analyzer_.process(in, frames);
    // The shrill guard judges the track by the analyser's residuals (lock-free read; see ShrillGuard).
    const auto r = analyzer_.liveResiduals();
    shrill_.setExcess(r.valid ? r.presenceDb : kUnknown, r.valid ? r.sizzleDb : kUnknown, r.centreDb);
  } else {
    shrill_.setExcess(kUnknown, kUnknown);
  }
  const int L = cfg_.oversample;
  bass_.setCharacter(bassCharacter_.load(std::memory_order_relaxed));
  bass_.setResolve(bassResolve_.load(std::memory_order_relaxed));
  const double xo = bassCrossover_.load(std::memory_order_relaxed);
  if (xo != appliedBassCrossover_) {
    bass_.setCrossoverHz(xo);
    appliedBassCrossover_ = xo;
  }

  for (int start = 0; start < frames; start += cfg_.maxBlock) {
    const int n = std::min(cfg_.maxBlock, frames - start);
    const float* src = in + static_cast<size_t>(start) * C;
    float* dst = out + static_cast<size_t>(start) * C;
    const double gain = std::pow(10.0, appliedGainDb() / 20.0);
    if(!gainInitialized_) { smoothedGain_=gain;gainTarget_=gain;gainInitialized_=true; }
    else if(gain!=gainTarget_) {
      gainTarget_=gain;
      gainRampRemaining_=std::max(1,static_cast<int>(std::round(cfg_.sampleRate*.010)));
      gainStep_=(gainTarget_-smoothedGain_)/gainRampRemaining_;
    }
    for(int i=0;i<n;++i) {
      gains_[i]=smoothedGain_;
      if(gainRampRemaining_>0) { smoothedGain_+=gainStep_;if(--gainRampRemaining_==0)smoothedGain_=gainTarget_; }
    }
    const bool protect=gainProtection_.load(std::memory_order_relaxed);
    // AGP gain is applied *after* the EQ. The 64-bit chain cannot clip
    // internally, so this is equivalent to lowering the preamp, but it never
    // leaves filter state out of step with the new gain (which would overshoot
    // again on the next chunk and over-reduce).
    const double agpGain = std::pow(10.0, agpDb_.load(std::memory_order_relaxed) / 20.0);
    // All channels of the chunk are processed before any output is written,
    // so gain protection can scale the whole chunk consistently.
    double peak = 0.0;
    for (int ch = 0; ch < C; ++ch) {
      double* y = &outBuf_[static_cast<size_t>(ch) * cfg_.maxBlock];
      for (int i = 0; i < n; ++i) y[i] = std::isfinite(src[i*C+ch]) ? static_cast<double>(src[i*C+ch])*gains_[i] : 0.;
      if (L > 1) {
        os_[ch]->up(y, n, high_.data());
        if(!lab_)eq_.process(ch, high_.data(), n * L);
        os_[ch]->down(high_.data(), n, y);
      } else {
        if(!lab_)eq_.process(ch, y, n);
      }
      if (!lab_ && C != 2) bass_.process(ch, y, n);  // bass needs no oversampling; runs at the base rate
    }
    if(lab_) {
      lab_->process(outBuf_.data(),C==2?&outBuf_[cfg_.maxBlock]:nullptr,n);
      if(C==1)bass_.process(0,outBuf_.data(),n);
    }
    if (C == 2) bass_.processLinked(&outBuf_[0], &outBuf_[static_cast<size_t>(cfg_.maxBlock)], n);  // one gain for both channels
    if (C == 2) unmask_.process(&outBuf_[0], &outBuf_[static_cast<size_t>(cfg_.maxBlock)], n);
    else if (C == 1) unmask_.process(&outBuf_[0], nullptr, n);
    dynamic_.yieldLowLanes(unmask_.cutting());
    // The preamp scratch is free after EQ; reuse it for sample-aligned manual
    // de-harsh coefficients, including any fixed spatial delay.
    if (C == 2) stereo_.process(&outBuf_[0], &outBuf_[static_cast<size_t>(cfg_.maxBlock)], n, gains_.data());
    if (C == 2) grounding_.process(&outBuf_[0], &outBuf_[static_cast<size_t>(cfg_.maxBlock)], n);
    else if (C == 1) grounding_.process(&outBuf_[0], nullptr, n);
    // Sustained shrill is reduced after grounding (which handles transient spikes) and before the texture,
    // so the texture's harmonics are never reduced by it. The texture comes after grounding, so its harmonics
    // are not saturated a second time; the dynamic EQ still gets the last word on resonances.
    // One budget for 3-6 kHz (docs/BUILD_BRIEF_0.5.14.md WP6): the guard's presence band takes only what the grounding
    // restraint (this block), the vocal de-harsh (this block) and the upper dynamic-EQ lanes (last block) have left.
    {
      const auto dyn = dynamic_.reductionsDb();
      const double used = -std::min(0.0, grounding_.restraintDb()) - std::min(0.0, stereo_.lastDeharshDb()) -
                          std::min({0.0, dyn[2], dyn[3]});
      shrill_.setPresenceCap(kTopBudgetDb - used);
    }
    shrill_.process(&outBuf_[0], C==2?&outBuf_[cfg_.maxBlock]:nullptr, n);
    texture_.process(&outBuf_[0], C==2?&outBuf_[cfg_.maxBlock]:nullptr, n);
    dynamic_.process(&outBuf_[0],C==2?&outBuf_[cfg_.maxBlock]:nullptr,n,dynamicAmount_.load(std::memory_order_relaxed),C==2?gains_.data():nullptr);
    const auto reductions=dynamic_.reductionsDb();for(int b=0;b<4;++b)dynamicDb_[b].store(reductions[b],std::memory_order_relaxed);
    if(cfg_.truePeak) {
      if(gainResetPending_.exchange(false))limiter_.resetGain();
      limiter_.process(outBuf_.data(),cfg_.maxBlock,n,protect);
      agpDb_.store(limiter_.reductionDb(),std::memory_order_relaxed);
      for(int ch=0;ch<C;++ch)for(int i=0;i<n;++i)dst[i*C+ch]=static_cast<float>(dither_[ch].process(outBuf_[ch*cfg_.maxBlock+i]));
      continue;
    }
    for (int ch = 0; ch < C; ++ch) {
      const double* y = &outBuf_[static_cast<size_t>(ch) * cfg_.maxBlock];
      for (int i = 0; i < n; ++i) peak = std::max(peak, std::fabs(y[i]));
    }
    // Block peak detection gives a conservative target shared by both channels.
    // Release continuously toward that target so a past overload cannot leave
    // later music permanently attenuated. No extra lookahead buffer is added.
    const double target = protect && peak > kAgpCeiling ? kAgpCeiling / peak : 1.0;
    double scale = protect ? std::min(agpGain, target) : 1.0;
    const double release = std::exp(-1.0 / (0.250 * cfg_.sampleRate));
    for (int i = 0; i < n; ++i) {
      scale = target + release * (scale - target);
      for (int ch = 0; ch < C; ++ch) {
        const double y = outBuf_[static_cast<size_t>(ch) * cfg_.maxBlock + i];
        dst[i * C + ch] = static_cast<float>(dither_[ch].process(y * scale));
      }
    }
    agpDb_.store(20.0 * std::log10(scale), std::memory_order_relaxed);
  }
}

}  // namespace eqcore

#pragma once
// Original windowed-sinc reconstructed-peak limiter. 8 phases, 128 taps,
// 3 ms lookahead plus 64 detector frames. Stereo/channel linked; no allocation
// or locks in process. Not a BS.1770 compliance certification.
// -1.3 dB target includes 0.3 dB reserve for finite reconstruction differences.
#include <array>
#include <vector>
#include <cstdint>
namespace eqcore {
class TruePeakLimiter {
 public:
  TruePeakLimiter(double fs,int channels);
  int latencyFrames() const { return delay_; }
  void reset();
  void resetGain(){gain_=1;wasEnabled_=false;}
  void process(double* planar,int stride,int frames,bool enabled);
  double reductionDb() const;
 private:
  static constexpr int taps=128,phases=8;
  int channels_,lookahead_,delay_,ringSize_,pos_=0,hpos_=0;
  std::int64_t clock_=0;
  std::array<std::array<double,taps>,phases> coeff_{};
  std::vector<double> history_,audio_,queuePeak_;
  std::vector<std::int64_t> queueTime_;
  int head_=0,count_=0;
  double gain_=1,release_,attackSamples_;
  bool wasEnabled_=false;
};
}

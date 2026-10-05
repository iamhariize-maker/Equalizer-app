#pragma once
// Four linked, reduction-only parallel bandpass cuts. Detectors compare each
// resonance with its two neighbours. Sustained evidence, not an instrument or
// genre classifier. Sum of requested attenuation <= 3 dB, no automatic boost.
#include <array>
#include "eqcore/biquad.h"
namespace eqcore {
class DynamicEq {
 public:
  explicit DynamicEq(double fs);
  void reset();
  void process(double* left,double* right,int frames,double amount);
  std::array<double,4> reductionsDb() const {return db_;}
 private:
  struct Lane {Biquad detector[2][3],filter[2];double power[3]={},gain=1;int sustained=0;};
  std::array<Lane,4> lanes_{};
  std::array<double,4> db_{};
  double detectorRelease_,attack_,release_,activityRelease_,activityPower_=0;int hold_;
  std::array<bool,4> supported_{};
};
}

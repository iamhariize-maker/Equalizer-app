#pragma once
// Four linked, reduction-only parallel bandpass cuts. Detectors compare each
// resonance with its two neighbours. Sustained evidence, not an instrument or
// genre classifier. Sum of requested attenuation <= 3 dB, no automatic boost.
#include <array>
#include <complex>
#include "eqcore/biquad.h"
namespace eqcore {
class DynamicEq {
 public:
  explicit DynamicEq(double fs);
  void reset();
  // Optional sample-aligned manual mid de-harsh coefficients. In the two
  // overlapping high lanes, their centre-frequency mid response shares the
  // stronger requested attenuation instead of adding. This is a lane-centre
  // budget, not a bound on every frequency of a broadband signal. Side and
  // low-lane cuts remain; reductionsDb reports the linked side/maximum cut.
  void process(double* left,double* right,int frames,double amount,const double* vocalDeharshGains=nullptr);
  std::array<double,4> reductionsDb() const {return db_;}
  // While Bass Resolve is cutting, its lanes own the low range: the 120 and 330 Hz lanes here stop
  // requesting new attenuation (existing cuts release normally), so one note is never cut twice.
  void yieldLowLanes(bool yield){yieldLow_=yield;}
 private:
  struct Lane {Biquad detector[2][3],filter[2];double power[3]={},gain=1;int sustained=0;};
  void applyLane(int band,double (&x)[2],bool stereo,double vocalDeharshGain);
  std::array<Lane,4> lanes_{};
  std::array<double,4> db_{};
  double detectorRelease_,attack_,release_,activityRelease_,activityPower_=0;int hold_;
  int bypassFrames_,bypassRemaining_=0;
  std::array<bool,4> supported_{};
  bool yieldLow_=false;
  std::array<std::complex<double>,2> vocalBandAtHigh_{};
};
}

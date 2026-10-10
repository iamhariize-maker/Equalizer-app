#pragma once
// Original streaming realization of Svan's published reference-model controls.
// Symmetric sqrt-Hann, hop N/2, fixed unity Nyquist. No vendor implementation claim.
// Immutable per capture epoch: all storage/tables are prepared before process().
#include <array>
#include <complex>
#include <vector>
#include "eqcore/biquad.h"
namespace eqcore {
struct LabEqConfig {
  int block=0; // 0: normal native static EQ; otherwise 2048/4096/8192
  std::vector<int> stops;
  std::vector<double> gainsDb;
  std::array<BiquadCoeffs,2> bass{}; // exact reference coefficients, not RBJ substitutes
  double inputGainDb=0;
};
class LabEq {
 public:
  LabEq(const LabEqConfig& config,int channels);
  int latencyFrames() const { return n_; }
  double binGain(int bin) const { return gains_.at(bin); }
  void process(double* left,double* right,int frames);
  void reset();
 private:
  using Cx=std::complex<double>;
  void fft(bool inverse);
  void frame(int channel);
  void overlap(int channel);
  int n_,hop_,channels_,sinceFrame_=0;
  long long pos_=0;
  std::vector<double> window_,gains_,ring_,acc_;
  std::vector<Cx> work_,twiddle_;
  std::vector<int> bitrev_;
  std::array<std::array<Biquad,2>,2> bass_{};
};
} // namespace eqcore

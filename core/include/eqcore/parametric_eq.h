#pragma once
#include <array>
#include <atomic>
#include <mutex>
#include <vector>

#include "eqcore/biquad.h"

namespace eqcore {

constexpr int kMaxBandsPerChannel = 256;

// Multi-band parametric EQ with independent band lists per channel.
//
// Threading: setBands() may be called from any thread. process() is
// wait-free: it only try_locks the publication mutex at block start and keeps
// running with the previous coefficients if the lock is busy.
class ParametricEq {
 public:
  ParametricEq(int channels, double sampleRate);

  int channels() const { return channels_; }
  double sampleRate() const { return fs_; }

  // Replaces the bands of one channel (extra bands beyond
  // kMaxBandsPerChannel are ignored). Filter state of surviving bands is kept.
  void setBands(int channel, const std::vector<BandParams>& bands);

  // In-place processing of one channel. Allocation-free.
  void process(int channel, double* data, int frames);

  // Response of the *published-or-pending* configuration (UI thread).
  double responseDb(int channel, double freqHz) const;
  // Largest positive gain (dB, >= 0) over a log grid in [minHz, maxHz].
  double peakGainDb(int channel, double minHz, double maxHz, int points = 2048) const;

  void reset();

 private:
  struct Chain {
    std::vector<BiquadCoeffs> coeffs;
  };
  struct Runtime {
    std::vector<BiquadCoeffs> coeffs;
    std::vector<std::array<double, 2>> z;  // TDF2 state per section
  };

  int channels_;
  double fs_;
  mutable std::mutex mu_;              // guards pending_ and dirty_
  std::vector<Chain> pending_;         // written by UI thread
  std::vector<std::atomic<bool>> dirty_;
  std::vector<Runtime> rt_;            // audio thread only
};

}  // namespace eqcore

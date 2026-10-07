#pragma once
// Streaming spatial-residual enhancer (AQ-02): dry mid/side plus a bounded delta.
//
// The dry mid and side are only *delayed* (by exactly latencyFrames()), never filtered, so a
// hard-panned source cannot change sides and the mid signal is bit-identical to the input.
// What is added is a delta on the SIDE channel only, built from the part of S that is NOT
// explained by M in each frequency bin:
//
//     beta = E[S conj(M)] / (E[|M|^2] + lambda),   A = S - beta * M
//
// A is a decorrelated residual: ambience, double-tracking, wide instruments, reverb or noise.
// It is NOT source separation. A coherent (panned or centred) source leaves A near zero and is
// left alone, including a centred backing vocal.
//
// Weighted overlap-add: sqrt-Hann analysis and synthesis, frame N = nextPow2(fs * 1024 / 48000),
// hop N/4, so the delay is fixed at N frames (1024 at 44.1/48 kHz, 2048 at 96 kHz) and does not
// depend on block size. All buffers and tables are allocated in the constructor.
//
// Controls (0..1): backing (vocal-body band, up to 4 dB of residual) and binaural (body to air,
// up to 3 dB); the combined request is capped at 4 dB. Guards, all smooth:
//   - coherence: bins where S and M are >= 0.98 coherent get nothing (soft from 0.8)
//   - energy budget: per bin, the side power may not pass min(0.5 Pmm, Pss * 10^(4/10))
//     (exact quadratic root); the scale falls at once and recovers over ~100 ms
//   - onset/motion guard: a residual that suddenly exceeds its own long-term level is held back
//   - floors: silence and a 200 ms warm-up (then a 100 ms ramp) after start/reset
#include <atomic>
#include <complex>
#include <vector>

namespace eqcore {

class SpatialResidual {
 public:
  explicit SpatialResidual(double sampleRate);

  int latencyFrames() const { return n_; }

  // Any thread. backing, binaural in [0, 1]; non-finite values are treated as 0.
  void setParams(double backing, double binaural);

  // In place on one block of mid and side. Output is delayed by latencyFrames(); mid is exactly
  // the delayed input, side is the delayed input plus the bounded delta. Allocation-free.
  void process(double* mid, double* side, int frames);

  void reset();

  // Mean side-gain applied in the last frame, dB (diagnostics/tests).
  double lastDeltaDb() const { return lastDeltaDb_; }

 private:
  using Cx = std::complex<double>;
  void fft(Cx* x, bool inverse) const;
  void runFrame();

  double fs_;
  int n_, hop_, bins_;
  std::vector<double> window_;               // sqrt-Hann, periodic
  std::vector<Cx> twiddle_;
  std::vector<int> bitrev_;
  std::vector<double> midRing_, sideRing_;   // last n_ input samples
  std::vector<double> acc_;                  // overlap-add accumulator (delta only)
  std::vector<Cx> work_, spec_;              // FFT scratch
  std::vector<double> maskBacking_, maskBinaural_;
  // Per-bin statistics (slow 150 ms) and instantaneous-residual tracker (fast 30 ms).
  std::vector<double> pmm_, pss_, paSlow_, paFast_, scale_, gain_;
  std::vector<Cx> csm_;
  long long pos_ = 0;                        // input samples seen
  int sinceFrame_ = 0;
  double aSlow_, aFast_, aUp_, aParam_, aGuard_;
  double guard_ = 0, prevMid_ = 0, backingSm_ = 0, binauralSm_ = 0;
  double warmFrames_;
  double lastDeltaDb_ = 0;
  std::atomic<double> backing_{0}, binaural_{0};
};

}  // namespace eqcore

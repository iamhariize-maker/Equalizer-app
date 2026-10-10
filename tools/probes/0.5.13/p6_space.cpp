// Probe 6: side gain and hard-pan leak for three ways of building "space +1" (+6 dB nominal).
//   OLD  : s + (g-1)*LR4_HP180(s)               (dry-plus-delta, no shelf)       -- before 0.5.13
//   NEW  : as OLD with a 6 kHz shelf returning the air (0.5.13 as committed)
//   SEQ  : a plain M/S EQ on the side signal (bell + shelf), no crossover at all
#include <cmath>
#include <complex>
#include <cstdio>

#include "eqcore/biquad.h"

using namespace eqcore;
using C = std::complex<double>;

static double dB(C z) { return 20 * std::log10(std::abs(z)); }
static double leakDb(C h) { return 20 * std::log10(std::abs(C(1) - h) / std::abs(C(1) + h)); }  // hard-panned source, R re L

int main() {
  const double fs = 48000;
  const double g = std::pow(10.0, 6.0 / 20.0);
  const auto hp = designBiquad({FilterType::HighPass, 180, 0, 0.7071067811865476, true}, fs);
  const auto shelf = designBiquad({FilterType::HighShelf, 6000, -6.0, 0.7071067811865476, true}, fs);
  // SEQ candidates: broad bell centred on the presence region plus a shelf that returns the air.
  const auto bell = designBiquad({FilterType::Peak, 1500, 6.0, 0.45, true}, fs);
  const auto top = designBiquad({FilterType::HighShelf, 6500, -5.0, 0.7071067811865476, true}, fs);
  const auto low = designBiquad({FilterType::LowShelf, 250, -1.0, 0.7071067811865476, true}, fs);  // keep the bass image centred

  std::printf("%8s | %-22s | %-22s | %-26s\n", "Hz", "OLD gain / leak", "NEW gain / leak", "SEQ (bell+shelf) gain / leak");
  for (double f : {60.0, 120.0, 200.0, 300.0, 600.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0, 10000.0, 14000.0}) {
    const C h = responseAt(hp, f, fs);
    const C h2 = h * h;
    const C s = responseAt(shelf, f, fs);
    const C hOld = C(1) + h2 * (g - C(1));              // 1 + HP^2 (g - 1)
    const C hNew = C(1) + h2 * (g * s - C(1));
    const C hSeq = responseAt(bell, f, fs) * responseAt(top, f, fs) * responseAt(low, f, fs);
    std::printf("%8.0f | %+6.2f dB  %+7.1f dB | %+6.2f dB  %+7.1f dB | %+6.2f dB  %+7.1f dB\n", f, dB(hOld), leakDb(hOld), dB(hNew), leakDb(hNew), dB(hSeq), leakDb(hSeq));
  }
  return 0;
}

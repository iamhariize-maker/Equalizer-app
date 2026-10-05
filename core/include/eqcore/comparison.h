#pragma once
#include <vector>
#include <array>
namespace eqcore {
// Offline only. Same-length stereo renders, attenuation only. Returns before/
// after gated K-weighted levels + applied trims. Silence/short clips are rejected.
bool matchComparison(std::vector<float>& a,std::vector<float>& b,double fs,
                     std::array<double,6>& levels);
}

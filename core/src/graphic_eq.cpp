#include "eqcore/graphic_eq.h"
#include <algorithm>
#include <array>
#include <cmath>
namespace eqcore {
namespace {
struct ResponsePoint {
  double c1, s1, c2, s2;
  ResponsePoint(double f=0, double fs=48000) {
    const double w=6.2831853071795864769*f/fs;
    c1=std::cos(w); s1=-std::sin(w); c2=c1*c1-s1*s1; s2=2*c1*s1;
  }
  double db(const BiquadCoeffs& c) const {
    const double nr=c.b0+c.b1*c1+c.b2*c2, ni=c.b1*s1+c.b2*s2;
    const double dr=1+c.a1*c1+c.a2*c2, di=c.a1*s1+c.a2*s2;
    return 10*std::log10(std::max((nr*nr+ni*ni)/(dr*dr+di*di),1e-300));
  }
};
}
GraphicFit fitGraphicEq(const std::vector<BandParams>& target, int count, double fs) {
  GraphicFit fit;
  if ((count!=10 && count!=15 && count!=31 && count!=64) || !std::isfinite(fs) || fs<44100) return fit;
  constexpr int points=240;
  std::array<double,points> hz{}, desired{}, total{};
  std::array<ResponsePoint,points> grid{};
  const double ratio=std::pow(512.0,1.0/(count-1));
  const double q=std::sqrt(ratio)/(ratio-1);
  for(int i=0;i<points;++i) { hz[i]=20*std::pow(1000.0,i/(points-1.0)); grid[i]=ResponsePoint(hz[i],fs); }
  for(const auto& b:target) {
    if (!std::isfinite(b.freqHz) || !std::isfinite(b.gainDb) || !std::isfinite(b.q) || isIdentityBand(b)) continue;
    const auto c=designBiquad(b,fs);
    for(int i=0;i<points;++i) desired[i]+=grid[i].db(c);
  }
  for(int j=0;j<count;++j) fit.bands.push_back({j==0 ? FilterType::LowShelf : j==count-1 ? FilterType::HighShelf : FilterType::Peak,31.25*std::pow(ratio,j),0,(j==0 || j==count-1) ? .71 : q,true});
  std::vector<std::array<double,points>> current(static_cast<size_t>(count));
  // Coordinate Gauss-Newton with exact response updates, bounded steps, and
  // backtracking. Bells overlap; assigning target dB at centres would over-boost.
  for(int pass=0;pass<18;++pass) {
    double maxMove=0;
    for(int j=0;j<count;++j) {
      auto& b=fit.bands[j];
      auto plus=b, minus=b; plus.gainDb+=.05; minus.gainDb-=.05;
      const auto cp=designBiquad(plus,fs), cm=designBiquad(minus,fs);
      double numerator=0, denominator=1e-9, oldCost=0;
      for(int i=0;i<points;++i) {
        const double residual=desired[i]-total[i];
        const double derivative=(grid[i].db(cp)-grid[i].db(cm))/.1;
        numerator+=derivative*residual; denominator+=derivative*derivative; oldCost+=residual*residual;
      }
      double step=std::clamp(numerator/denominator,-4.0,4.0);
      for(int attempt=0;attempt<8;++attempt) {
        auto next=b; next.gainDb=std::clamp(b.gainDb+step,-12.0,12.0);
        const auto c=designBiquad(next,fs);
        std::array<double,points> proposed{};
        double cost=0;
        for(int i=0;i<points;++i) {
          proposed[i]=grid[i].db(c);
          const double error=desired[i]-(total[i]-current[j][i]+proposed[i]); cost+=error*error;
        }
        if(cost<=oldCost+1e-12) {
          maxMove=std::max(maxMove,std::abs(next.gainDb-b.gainDb)); b=next;
          for(int i=0;i<points;++i) total[i]+=proposed[i]-current[j][i];
          current[j]=proposed; break;
        }
        step*=.5;
      }
    }
    if(maxMove<.001) break;
  }
  double sum=0;
  for(int i=0;i<points;++i) { const double e=total[i]-desired[i]; sum+=e*e; fit.maxDb=std::max(fit.maxDb,std::abs(e)); }
  fit.rmsDb=std::sqrt(sum/points);
  return fit;
}
double positiveEqOverlapScale(const std::vector<BandParams>& bands, double ceiling, double fs) {
  if(!std::isfinite(ceiling) || ceiling<0 || !std::isfinite(fs) || fs<44100) return 0;
  std::array<ResponsePoint,512> grid{};
  for(size_t i=0;i<grid.size();++i) grid[i]=ResponsePoint(20*std::pow(1000.0,i/(grid.size()-1.0)),fs);
  auto peak=[&](double scale) {
    std::array<double,512> sum{};
    for(auto b:bands) {
      if (!std::isfinite(b.freqHz) || !std::isfinite(b.gainDb) || !std::isfinite(b.q) || isIdentityBand(b)) continue;
      if(b.gainDb>0) b.gainDb*=scale;
      const auto c=designBiquad(b,fs);
      for(size_t i=0;i<grid.size();++i) sum[i]+=grid[i].db(c);
    }
    return *std::max_element(sum.begin(),sum.end());
  };
  const double fullPeak=peak(1);
  if(fullPeak<=ceiling-.02 || (fullPeak<=ceiling && fullPeak<.02)) return 1;
  double lo=0,hi=1;
  for(int i=0;i<18;++i) {const double mid=(lo+hi)/2; if(peak(mid)<=std::max(0.0,ceiling-.02)) lo=mid; else hi=mid;}
  return lo;
}

}

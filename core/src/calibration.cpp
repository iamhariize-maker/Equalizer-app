#include "eqcore/calibration.h"
#include "eqcore/graphic_eq.h"
#include <algorithm>
#include <cmath>
namespace eqcore {
namespace {
CalibratedFit boundedFit(FrCurve curve,double amount,int bands,double low,double high) {
 CalibratedFit result;
 if(curve.empty()||!std::isfinite(amount)||bands<4||bands>128||low>30||high<10000)return result;
 amount=std::clamp(amount,0.,1.);
 curve=smooth(curve,1./6);auto treble=smooth(curve,.5);
 for(size_t i=0;i<curve.hz.size();++i) {
  double f=curve.hz[i],w=std::clamp(std::log2(f/6000.),0.,1.);
  double db=(1-w)*curve.db[i]+w*treble.db[i];
  db=std::clamp(db,-12.,f>6000?3.:6.);
  double edge=std::clamp(std::log2(f/low)*3,0.,1.)*std::clamp(std::log2(high/f)*3,0.,1.);
  curve.db[i]=db*amount*edge; // no extrapolation into unmeasured frequencies
 }
 result.fit=fitDenseBands(curve,bands);
 auto& fitted=result.fit.bands;
 double guard=positiveEqOverlapScale(fitted,6.);
 for(auto& b:fitted)if(b.gainDb>0)b.gainDb*=guard;
 // Report the actual final fitted filters, including the positive response guard.
 double sq=0,mx=0;for(int k=0;k<240;++k) {
  double f=30*std::pow(14000./30,k/239.);double actual=0;
  for(const auto& b:fitted)actual+=magnitudeDb(designBiquad(b,48000),f,48000);
  double error=actual-curve.at(f);sq+=error*error;mx=std::max(mx,std::abs(error));
 }
 result.fit.rmsErrorDb=std::sqrt(sq/240);result.fit.maxErrorDb=mx;
 result.lowHz=low;result.highHz=high;result.valid=true;return result;
}
}
CalibratedFit calibratedTuning(const FrCurve& m,const FrCurve& t,double amount,int bands,double bass,double tilt) {
 if(m.hz.size()<24||t.empty()||!std::isfinite(bass)||!std::isfinite(tilt))return {};
 TuningOptions opt;opt.maxBoostDb=6;opt.maxCutDb=12;opt.bassDb=std::clamp(bass,-6.,6.);opt.tiltDbPerOct=std::clamp(tilt,-.6,.6);
 return boundedFit(computeCorrection(m,t,opt),amount,bands,std::max(m.hz.front(),t.hz.front()),std::min(m.hz.back(),t.hz.back()));
}
CalibratedFit calibratedProfile(const FrCurve& c,double amount,int bands,double bass,double tilt) {
 if(c.empty()||!std::isfinite(bass)||!std::isfinite(tilt))return {};
 FrCurve shaped=c;double reference=0;
 for(int k=0;k<64;++k)reference+=c.at(300*std::pow(10.,k/63.))/64;
 for(size_t k=0;k<c.hz.size();++k) {
  double f=c.hz[k];shaped.db[k]=c.db[k]-reference+std::clamp(bass,-6.,6.)/(1+std::pow(f/105.,2))+std::clamp(tilt,-.6,.6)*std::log2(f/1000.);
 }
 return boundedFit(shaped,amount,bands,c.hz.front(),c.hz.back());
}
}

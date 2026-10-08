#include "eqcore/dynamic_eq.h"
#include <algorithm>
#include <cmath>
namespace eqcore {
DynamicEq::DynamicEq(double fs):detectorRelease_(std::exp(-1/(.040*fs))),
 attack_(std::exp(-1/(.020*fs))),release_(std::exp(-1/(.250*fs))),activityRelease_(std::exp(-1/(.005*fs))),hold_(int(.080*fs)),bypassFrames_(std::max(1,int(std::ceil(fs*.010)))) {
 const double frequencies[]={120,330,3000,6500};
 const auto vocalBand=designBiquad({FilterType::BandPass,3800,0,.9,true},fs);
 for(int b=2;b<4;++b)vocalBandAtHigh_[b-2]=responseAt(vocalBand,std::min(fs*.3,frequencies[b]),fs);
 for(int b=0;b<4;++b)supported_[b]=frequencies[b]*std::sqrt(2.)<fs*.45;
 for(int b=0;b<4;++b)for(int c=0;c<2;++c) {
  for(int j=0;j<3;++j)lanes_[b].detector[c][j].setCoeffs(designBiquad(
   {FilterType::BandPass,std::min(fs*.40,frequencies[b]*std::pow(2.,(j-1)*.5)),0,4.0,true},fs));
  lanes_[b].filter[c].setCoeffs(designBiquad({FilterType::BandPass,std::min(fs*.3,frequencies[b]),0,4.0,true},fs));
 }
}
void DynamicEq::reset() {
 for(auto& l:lanes_) {for(auto& c:l.detector)for(auto& f:c)f.reset();for(auto& f:l.filter)f.reset();
  std::fill(std::begin(l.power),std::end(l.power),0);l.gain=1;l.sustained=0;}
 db_.fill(0);activityPower_=0;bypassRemaining_=0;
}
void DynamicEq::applyLane(int band,double (&x)[2],bool stereo,double vocalDeharshGain) {
 auto& lane=lanes_[band];
 const double bp[2]={lane.filter[0].process(x[0]),lane.filter[1].process(x[1])};
 const double cut=lane.gain-1;
 if(stereo&&band>=2&&vocalDeharshGain<1) {
  // Keep the L/R filters and linked evidence advancing continuously. Weight
  // only their mid delta; no detection/filter basis switches at knob changes.
  const double manualMagnitude=std::abs(1.+(vocalDeharshGain-1)*vocalBandAtHigh_[band-2]);
  const double midGain=std::max(lane.gain,std::min(1.,lane.gain/std::max(1e-30,manualMagnitude)));
  const double midDelta=(midGain-1)*.5*(bp[0]+bp[1]),sideDelta=cut*.5*(bp[0]-bp[1]);
  x[0]+=midDelta+sideDelta;x[1]+=midDelta-sideDelta;
 } else { x[0]+=cut*bp[0];x[1]+=cut*bp[1]; }
}
void DynamicEq::process(double* left,double* right,int frames,double amount,const double* vocalDeharshGains) {
 amount=std::isfinite(amount)?std::clamp(amount,0.,1.):0;
 if(amount==0) {
  bool neutral=true;for(const auto& l:lanes_)neutral=neutral&&l.gain==1;
  if(neutral){reset();return;}
  if(bypassRemaining_==0)bypassRemaining_=bypassFrames_;
  // Fade existing parallel cuts to exact unity before clearing their histories.
  // The remaining count is retained across callbacks, independent of block size.
  for(int i=0;i<frames;++i) {
   double x[2]={left[i],right?right[i]:left[i]};
   for(int b=0;b<4;++b) {
    auto& l=lanes_[b];l.gain+=(1-l.gain)/bypassRemaining_;
    db_[b]=20*std::log10(std::max(1e-30,l.gain));
    applyLane(b,x,right!=nullptr,vocalDeharshGains?vocalDeharshGains[i]:1.);
   }
   left[i]=x[0];if(right)right[i]=x[1];
   if(--bypassRemaining_==0){reset();return;}
  }
  return;
 }
 bypassRemaining_=0;
 for(int i=0;i<frames;++i) {
  const double input[]={left[i],right?right[i]:left[i]};
  const double instant=(input[0]*input[0]+input[1]*input[1])*.5;
  activityPower_=instant+activityRelease_*(activityPower_-instant);
  std::array<double,4> wanted{};double sum=0;
  for(int b=0;b<4;++b) {
   auto& l=lanes_[b];for(int j=0;j<3;++j) {
    double p=0;for(int c=0;c<2;++c) {double v=l.detector[c][j].process(input[c]);p+=v*v*.5;}
    l.power[j]=p+detectorRelease_*(l.power[j]-p);
   }
   double excess=10*std::log10((l.power[1]+1e-20)/(std::max(l.power[0],l.power[2])+1e-20));
   bool evidence=supported_[b]&&activityPower_>3.16e-6&&activityPower_>l.power[1]*.1&&l.power[1]>3.16e-6 && excess>6; // > -55 dBFS; strong local prominence
   l.sustained=evidence?std::min(hold_,l.sustained+1):0;
   wanted[b]=amount*(l.sustained>=hold_&&!(yieldLow_&&b<2)?std::clamp((excess-6)*.5,0.,1.5):0.);
   sum+=wanted[b];
  }
  double actualBudget=0;
  for(int b=0;b<4;++b) {
   auto& l=lanes_[b];double target=std::pow(10.,-wanted[b]*(sum>3?3/sum:1)/20);
   double a=target<l.gain?attack_:release_;l.gain=target+a*(l.gain-target);
   db_[b]=20*std::log10(std::max(1e-30,l.gain));actualBudget-=db_[b];
  }
  double x[2]={input[0],input[1]};
  for(int b=0;b<4;++b) {
   auto& l=lanes_[b];if(actualBudget>3){db_[b]*=3/actualBudget;l.gain=std::pow(10.,db_[b]/20);}
   applyLane(b,x,right!=nullptr,vocalDeharshGains?vocalDeharshGains[i]:1.);
  }
  left[i]=x[0];if(right)right[i]=x[1];
 }
}
}

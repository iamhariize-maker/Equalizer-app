#include "eqcore/true_peak.h"
#include <algorithm>
#include <cmath>
namespace eqcore {
// -1 dB nominal headroom plus 0.3 dB finite-reconstruction reserve.
namespace { constexpr double pi=3.14159265358979323846,ceiling=.8609937521846006; }
TruePeakLimiter::TruePeakLimiter(double fs,int channels):channels_(std::max(1,channels)),
 lookahead_(std::max(1,int(std::ceil(fs*.003)))),delay_(64+lookahead_),ringSize_(delay_+129),
 history_(channels_*taps*2,0),audio_(channels_*ringSize_,0),
 queuePeak_(ringSize_+2,0),queueTime_(ringSize_+2,0),
 release_(std::exp(-1/(.250*fs))),attackSamples_(std::max(1.,fs*.001)) {
 for(int p=0;p<phases;++p) {
  double sum=0;
  for(int k=0;k<taps;++k) {
   double x=k-64+p/double(phases);
   // Hann window centred at each fractional sampling position.
   double w=std::abs(x)<64 ? .5+.5*std::cos(pi*x/64) : 0;
   coeff_[p][k]=(std::abs(x)<1e-12?1:std::sin(pi*x)/(pi*x))*w;
   sum+=coeff_[p][k];
  }
  for(auto& c:coeff_[p])c/=sum;
 }
}
void TruePeakLimiter::reset() {
 std::fill(history_.begin(),history_.end(),0);std::fill(audio_.begin(),audio_.end(),0);
 pos_=hpos_=head_=count_=0;clock_=0;gain_=1;wasEnabled_=false;
}
double TruePeakLimiter::reductionDb() const { return 20*std::log10(std::max(1e-30,gain_)); }
void TruePeakLimiter::process(double* data,int stride,int frames,bool enabled) {
 const int cap=int(queuePeak_.size());
 for(int i=0;i<frames;++i,++clock_) {
  double peak=0;
  for(int c=0;c<channels_;++c) {
   double v=data[c*stride+i];if(!std::isfinite(v))v=0;
   history_[c*taps*2+hpos_]=history_[c*taps*2+hpos_+taps]=v;audio_[c*ringSize_+pos_]=v;
   peak=std::max(peak,std::abs(v)); // also catch causal sample peaks
   // Mirrored history makes every convolution contiguous: no modulo in the hot loop.
   const double* history=history_.data()+c*taps*2+hpos_;
   for(int p=1;p<phases;++p) {
    double a=0,b=0,d=0,e=0;
    for(int k=0;k<taps;k+=4) {
     a+=coeff_[p][k]*history[k];b+=coeff_[p][k+1]*history[k+1];
     d+=coeff_[p][k+2]*history[k+2];e+=coeff_[p][k+3]*history[k+3];
    }
    const double y=(a+b)+(d+e);
    peak=std::max(peak,std::abs(y));
   }
  }
  while(count_&&queueTime_[head_]<=clock_-(lookahead_+128)) {head_=(head_+1)%cap;--count_;}
  while(count_&&queuePeak_[(head_+count_-1)%cap]<=peak)--count_;
  const int tail=(head_+count_)%cap;queuePeak_[tail]=peak;queueTime_[tail]=clock_;++count_;
  const double target=enabled ? std::min(1.,ceiling/std::max(1e-30,queuePeak_[head_])) : 1.;
  if(enabled&&!wasEnabled_)gain_=target;
  if(!enabled)gain_=1;
  else if(target<gain_)gain_=std::max(target,gain_-(1-target)/attackSamples_);
  else gain_=target+release_*(gain_-target);
  // Startup is naturally silent; output is delayed identically during bypass.
  const int read=(pos_-delay_+ringSize_)%ringSize_;
  double sample=0;for(int c=0;c<channels_;++c)sample=std::max(sample,std::abs(audio_[c*ringSize_+read]));
  const double safe=enabled?std::min(gain_,ceiling/std::max(1e-30,sample)):1.;
  for(int c=0;c<channels_;++c)data[c*stride+i]=audio_[c*ringSize_+read]*safe;
  wasEnabled_=enabled;hpos_=(hpos_+taps-1)%taps;pos_=(pos_+1)%ringSize_;
 }
}
}

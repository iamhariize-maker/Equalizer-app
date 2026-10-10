#include "eqcore/lab_eq.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>
namespace eqcore {
namespace { constexpr double pi=3.14159265358979323846; }
LabEq::LabEq(const LabEqConfig& c,int channels):n_(c.block),hop_(c.block/2),channels_(channels) {
  if((n_!=2048&&n_!=4096&&n_!=8192)||(channels!=1&&channels!=2)||
      c.stops.empty()||c.stops.size()>128||c.stops.size()!=c.gainsDb.size()||
      c.stops.back()!=n_/2||!std::isfinite(c.inputGainDb)||c.inputGainDb>0||c.inputGainDb<-60)
    throw std::invalid_argument("Invalid capture Lab configuration");
  int previous=-1;
  for(size_t i=0;i<c.stops.size();++i) {
    if(c.stops[i]<=previous||c.stops[i]>n_/2||!std::isfinite(c.gainsDb[i])||c.gainsDb[i]<-18||c.gainsDb[i]>12)
      throw std::invalid_argument("Invalid capture Lab bands");
    previous=c.stops[i];
  }
  for(const auto& b:c.bass) {
    if(!std::isfinite(b.b0)||!std::isfinite(b.b1)||!std::isfinite(b.b2)||!std::isfinite(b.a1)||!std::isfinite(b.a2)||
        std::abs(b.a2)>=1||1+b.a1+b.a2<=0||1-b.a1+b.a2<=0)
      throw std::invalid_argument("Invalid capture Lab biquad");
  }
  window_.resize(n_);gains_.resize(n_/2+1);work_.resize(n_);twiddle_.resize(n_/2);bitrev_.resize(n_);
  ring_.assign(n_*channels_,0);acc_.assign(n_*channels_,0);
  int bits=0;while((1<<bits)<n_)++bits;
  for(int i=0;i<n_;++i) {
    window_[i]=std::sqrt(.5*(1-std::cos(2*pi*i/(n_-1))));
    int reversed=0;for(int j=0;j<bits;++j)reversed=(reversed<<1)|((i>>j)&1);bitrev_[i]=reversed;
  }
  for(int i=0;i<n_/2;++i)twiddle_[i]=std::polar(1.,-2*pi*i/n_);
  size_t band=0;
  for(int k=0;k<=n_/2;++k) {
    while(k>c.stops[band])++band;
    gains_[k]=k==n_/2?1.:std::pow(10.,c.gainsDb[band]/20);
  }
  for(int ch=0;ch<channels_;++ch)for(int b=0;b<2;++b)bass_[ch][b].setCoeffs(c.bass[b]);
}
void LabEq::fft(bool inverse) {
  for(int i=0;i<n_;++i)if(i<bitrev_[i])std::swap(work_[i],work_[bitrev_[i]]);
  for(int length=2;length<=n_;length*=2) {
    int half=length/2,step=n_/length;
    for(int start=0;start<n_;start+=length)for(int j=0;j<half;++j) {
      const auto t=inverse?std::conj(twiddle_[j*step]):twiddle_[j*step];
      const auto u=work_[start+j],v=work_[start+j+half]*t;
      work_[start+j]=u+v;work_[start+j+half]=u-v;
    }
  }
  if(inverse)for(auto& x:work_)x/=n_;
}
void LabEq::frame(int ch) {
  const int begin=static_cast<int>(pos_&(n_-1)),offset=ch*n_;
  for(int i=0;i<n_;++i)work_[i]=ring_[offset+((begin+i)&(n_-1))]*window_[i];
  fft(false);
  for(int k=0;k<n_;++k)work_[k]*=gains_[std::min(k,n_-k)];
  fft(true);
  overlap(ch);
}
void LabEq::overlap(int ch) {
  const int begin=static_cast<int>(pos_&(n_-1)),offset=ch*n_;
  for(int i=0;i<n_;++i)acc_[offset+((begin+i)&(n_-1))]+=work_[i].real()*window_[i];
}
void LabEq::process(double* left,double* right,int frames) {
  for(int i=0;i<frames;++i) {
    const int index=static_cast<int>(pos_&(n_-1));
    for(int ch=0;ch<channels_;++ch) {
      double* data=ch?right:left;double input=data[i];
      for(auto& b:bass_[ch])input=b.process(input);
      ring_[ch*n_+index]=input;
      data[i]=acc_[ch*n_+index];acc_[ch*n_+index]=0;
    }
    ++pos_;
    if(++sinceFrame_==hop_) {
      frame(0);
      if(channels_==2) {
        // Exact mono can share spectral work, never accumulator/filter histories. The comparison
        // short-circuits on stereo input; near-mono is not rounded or treated as identical.
        if(std::equal(ring_.begin(),ring_.begin()+n_,ring_.begin()+n_))overlap(1);
        else frame(1);
      }
      sinceFrame_=0;
    }
  }
}
void LabEq::reset() {
  std::fill(ring_.begin(),ring_.end(),0);std::fill(acc_.begin(),acc_.end(),0);
  pos_=0;sinceFrame_=0;for(auto& channel:bass_)for(auto& b:channel)b.reset();
}
} // namespace eqcore

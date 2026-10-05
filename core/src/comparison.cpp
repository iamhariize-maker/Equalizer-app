#include "eqcore/comparison.h"
#include "eqcore/analyzer.h"
#include <algorithm>
#include <cmath>
namespace eqcore {
namespace {
SourceFeatures features(const std::vector<float>& x,double fs) {
 SourceAnalyzer analyzer(fs,2);analyzer.process(x.data(),int(x.size()/2));return analyzer.snapshot();
}
}
bool matchComparison(std::vector<float>& a,std::vector<float>& b,double fs,std::array<double,6>& levels) {
 if(a.size()!=b.size()||a.size()<fs*2*4||a.size()>fs*2*15||fs<16000||fs>96000)return false;
 for(const auto* x:{&a,&b})for(float v:*x)if(!std::isfinite(v)||std::abs(v)>16)return false;
 const auto fa=features(a,fs),fb=features(b,fs);
 if(!fa.valid||!fb.valid||fa.loudnessLufs<-60||fb.loudnessLufs<-60)return false;
 double target=std::min({fa.loudnessLufs,fb.loudnessLufs,-18.});
 levels={fa.loudnessLufs,fb.loudnessLufs,0,0,target-fa.loudnessLufs,target-fb.loudnessLufs};
 const double ga=std::pow(10.,levels[4]/20),gb=std::pow(10.,levels[5]/20);
 for(size_t i=0;i<a.size();++i){a[i]*=ga;b[i]*=gb;}
 levels[2]=features(a,fs).loudnessLufs;levels[3]=features(b,fs).loudnessLufs;
 return std::abs(levels[2]-levels[3])<=.1;
}
}

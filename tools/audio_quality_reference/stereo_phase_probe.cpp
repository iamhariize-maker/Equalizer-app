#include "eqcore/stereo.h"
#include <cmath>
#include <cstdio>
#include <vector>
int main(){constexpr double pi=3.14159265358979323846; const double fs=48000; const int n=96000;
 std::puts("control,amount,frequency_hz,left_gain_db,right_leak_relative_input_db,mono_error");
 for(int c=0;c<2;c++)for(double amount:{0.000001,0.01,1.0})for(double hz:{60.,180.,500.,1600.,8000.}){
  eqcore::StereoTuner t(fs);eqcore::StereoTunerParams p;if(c==0)p.backingVocals=amount;else p.spatialDetail=amount;t.setParams(p);
  std::vector<double> l(n),r(n),dry(n);for(int i=0;i<n;i++)l[i]=dry[i]=.1*std::sin(2*pi*hz*i/fs);
  t.process(l.data(),r.data(),n);double el=0,er=0,ed=0,err=0;
  for(int i=n/2;i<n;i++){el+=l[i]*l[i];er+=r[i]*r[i];ed+=dry[i]*dry[i];err=std::max(err,std::abs(l[i]+r[i]-dry[i]));}
  std::printf("%s,%.6f,%.0f,%.5f,%.5f,%.3g\n",c==0?"backing":"binaural",amount,hz,10*std::log10(el/ed),10*std::log10(std::max(er/ed,1e-30)),err);
 }
}

#include "quality_policy.h"
#include <cstdlib>
#include <iostream>
#include <limits>
#include <random>
using namespace svan_reference;
int checks=0;
void check(bool ok,const char* what){++checks;if(!ok){std::cerr<<"FAIL "<<what<<'\n';std::exit(1);}}
int main(){
  Evidence e{true,true,1,0};
  check(resolve({Owner::Off,1,1},true,1,e)==0,"explicit off");
  check(resolve({Owner::Manual,.35,1},true,.9,e)==.35,"manual owns its value");
  check(resolve({Owner::Auto,0,.7},true,.9,e)==.7,"auto ceiling");
  check(resolve({Owner::Auto,0,1},false,1,e)==0,"auto master off");
  e.ageSeconds=1;check(resolve({Owner::Auto,0,1},true,1,e)==0,"stale auto evidence");
  e={false,true,1,0};check(resolve({Owner::Manual,1,1},true,1,e)==0,"system route unavailable");
  e={true,false,1,0};check(resolve({Owner::Auto,0,1},true,1,e)==0,"route epoch mismatch");
  check(sideScale(0,1,1,1)==0,"pure side not boosted");
  check(sideScale(1,.6,.1,.1)==0,"already wide not boosted");
  check(sideScale(1,.1,.1,1)==0,"invalid moments rejected");
  check(sideScale(1,.1,.1,std::numeric_limits<double>::quiet_NaN())==0,"NaN rejected");
  auto p=StereoMoments{1,.09,{.3,0}};
  auto r=decorrelatedResidual({1,0},{.3,0},p);
  check(!r.eligible && std::abs(r.value)==0,"coherent panned primary unchanged");
  r=decorrelatedResidual({1,0},{0,.2},{1,.04,{0,0}});
  check(r.eligible && std::abs(r.value-std::complex<double>(0,.2))<1e-14,"decorrelated residual available");
  check(detailGainDb(1,1,1,1,1,0)==4,"shared stereo budget");
  check(detailGainDb(1,1,1,1,1,1)==0,"transient guard");
  BassEvidence b{true,true,1,0,-20,200,200,12,0};
  check(bassReductionDb(1,b)==1.5,"eligible excess limited to 1.5dB");
  b.harmonicProtection=1;check(bassReductionDb(1,b)==0,"sustained note protected");
  b.harmonicProtection=0;b.onsetAgeMs=0;check(bassReductionDb(1,b)==0,"onset protected");
  b.onsetAgeMs=200;b.valid=false;check(bassReductionDb(1,b)==0,"unknown is not no harmonics");
  b.valid=true;b.ageSeconds=1;check(bassReductionDb(1,b)==0,"stale bass evidence neutral");
  auto db=reductionBudget<4>({1.5,1.5,.5,.5},2);
  check(std::abs(db[0]+db[1]+db[2]+db[3]-2)<1e-12,"shared cut budget");
  check(guardedCharacterDb(-12,1,false)==-.75,"sustain not shortened by strong Feel");
  check(guardedCharacterDb(12,1,true)==1.5,"attack not over-sharpened");
  check(guardedCharacterDb(12,0,false)==12,"resolve off retains requested limit");
  check(oversamplingFor(48000,2)==4 && oversamplingFor(96000,2)==2 &&
        oversamplingFor(192000,2)==1,"avoid 96/192k over-oversampling");
  check(oversamplingFor(88200,2)==2 && oversamplingFor(176400,2)==1,"44.1 family");
  for(double fs:{44100.,48000.,88200.,96000.,176400.,192000.}) {
    DbSmoother a,b;
    const int n=int(fs);
    for(int i=0;i<n;++i)a.tick(1.5,fs);
    for(int start=0;start<n;start+=127)for(int i=start;i<std::min(n,start+127);++i)b.tick(1.5,fs);
    check(a.value==b.value,"smoothing block independence");
    check(std::abs(a.value-1.5)<1e-7,"smoothing settles at every rate");
  }
  // Property test against the full quadratic, using realizable complex vectors.
  std::mt19937_64 gen(0x5356414e);
  std::uniform_real_distribution<double> u(-1.,1.);
  for(int trial=0;trial<5000;++trial){
    double pm=0,ps=0,pd=0,q=0;
    for(int k=0;k<8;++k){
      const std::complex<double> m{u(gen),u(gen)},s{.2*u(gen),.2*u(gen)},d{u(gen),u(gen)};
      pm+=std::norm(m);ps+=std::norm(s);pd+=std::norm(d);q+=std::real(s*std::conj(d));
    }
    double scale=sideScale(pm,ps,pd,q);
    double result=ps+2*scale*q+scale*scale*pd;
    double limit=std::min(.5*pm,ps*std::pow(10.,.4));
    check(result<=limit+1e-11,"side energy stays in both budgets");
  }
  std::cout<<"PASS "<<checks<<" reference-policy assertions (including 5000 quadratic properties)\n";
}

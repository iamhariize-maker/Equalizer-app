#pragma once
#include "eqcore/tuning.h"
namespace eqcore {
// Measurement-derived target correction, with explicit coverage and conservative
// treble/boost limits. A published profile is a separate input, not a personal
// measurement. No SPL/hearing calibration is inferred from frequency response.
struct CalibratedFit {
 DenseFit fit;double lowHz=0,highHz=0;bool valid=false;
};
CalibratedFit calibratedTuning(const FrCurve& measurement,const FrCurve& target,
                              double amount,int bands,double bass=0,double tilt=0);
CalibratedFit calibratedProfile(const FrCurve& correction,double amount,int bands,
                               double bass=0,double tilt=0);
}

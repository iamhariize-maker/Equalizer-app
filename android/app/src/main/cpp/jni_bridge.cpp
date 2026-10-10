// JNI surface for app.svan.NativeEngine.
#include <jni.h>

#include <string>
#include <vector>
#include <stdexcept>

#include "eqcore/autoeq.h"
#include "eqcore/calibration.h"
#include "eqcore/comparison.h"
#include "eqcore/engine.h"
#include "eqcore/graphic_eq.h"
#include "eqcore/policy.h"
#include "eqcore/svaramanas.h"
#include "eqcore/tuning.h"

using namespace eqcore;

namespace {
Engine* fromHandle(jlong h) { return reinterpret_cast<Engine*>(h); }

FilterType typeFromInt(jint t) {
  switch (t) {
    case 1: return FilterType::LowShelf;
    case 2: return FilterType::HighShelf;
    case 3: return FilterType::LowPass;
    case 4: return FilterType::HighPass;
    case 5: return FilterType::BandPass;
    case 6: return FilterType::Notch;
    case 7: return FilterType::AllPass;
    default: return FilterType::Peak;
  }
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_svan_NativeEngine_nativeCreateLab(
    JNIEnv* env,jclass,jint rate,jint channels,jint oversample,jdouble stopbandDb,jint ditherBits,
    jint ditherMode,jboolean autoHeadroom,jboolean gainProtection,jboolean spatialResidual,
    jint block,jintArray stops,jdoubleArray gains,jdoubleArray coefficients,jdouble inputGainDb) {
  try {
    const int count=env->GetArrayLength(stops);
    if(count<1||count>128||env->GetArrayLength(gains)!=count||env->GetArrayLength(coefficients)!=10||
        (rate!=44100&&rate!=48000))throw std::invalid_argument("Unsupported capture Lab format");
    EngineConfig c;c.truePeak=true;c.sampleRate=rate;c.channels=channels;c.oversample=oversample;
    c.stopbandDb=stopbandDb;c.ditherBits=ditherBits;c.ditherMode=static_cast<DitherMode>(ditherMode);
    c.autoHeadroom=autoHeadroom;c.gainProtection=gainProtection;c.spatialResidual=spatialResidual;
    c.lab.block=block;c.lab.inputGainDb=inputGainDb;
    std::vector<jint> bins(count);env->GetIntArrayRegion(stops,0,count,bins.data());
    c.lab.stops.assign(bins.begin(),bins.end());c.lab.gainsDb.resize(count);
    env->GetDoubleArrayRegion(gains,0,count,c.lab.gainsDb.data());
    double biquads[10];env->GetDoubleArrayRegion(coefficients,0,10,biquads);
    for(int b=0;b<2;++b)c.lab.bass[b]={biquads[b*5],biquads[b*5+1],biquads[b*5+2],biquads[b*5+3],biquads[b*5+4]};
    if(env->ExceptionCheck())return 0;
    return reinterpret_cast<jlong>(new Engine(c));
  } catch(const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),e.what());return 0;
  }
}

JNIEXPORT jlong JNICALL Java_app_svan_NativeEngine_nativeCreate(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint quality, jint outputBits) {
  const auto mode = static_cast<QualityMode>(quality < 0 || quality > 3 ? 0 : quality);
  auto cfg = EngineConfig::forQuality(mode, sampleRate, channels, outputBits);
  return reinterpret_cast<jlong>(new Engine(cfg));
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeDestroy(JNIEnv*, jclass, jlong h) {
  delete fromHandle(h);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBands(
    JNIEnv* env, jclass, jlong h, jint channel, jintArray types, jdoubleArray freqs,
    jdoubleArray gains, jdoubleArray qs, jbooleanArray enabled) {
  const jsize n = env->GetArrayLength(types);
  if (env->GetArrayLength(freqs) != n || env->GetArrayLength(gains) != n ||
      env->GetArrayLength(qs) != n || env->GetArrayLength(enabled) != n)
    return;
  std::vector<jint> t(n);
  std::vector<jdouble> f(n), g(n), q(n);
  env->GetIntArrayRegion(types, 0, n, t.data());
  env->GetDoubleArrayRegion(freqs, 0, n, f.data());
  env->GetDoubleArrayRegion(gains, 0, n, g.data());
  env->GetDoubleArrayRegion(qs, 0, n, q.data());
  std::vector<jboolean> on(n);
  env->GetBooleanArrayRegion(enabled, 0, n, on.data());
  std::vector<BandParams> bands(n);
  for (jsize i = 0; i < n; ++i) bands[i] = {typeFromInt(t[i]), f[i], g[i], q[i], on[i] == JNI_TRUE};
  if (channel < 0) fromHandle(h)->setBandsAllChannels(bands);
  else fromHandle(h)->setBands(channel, bands);
}

JNIEXPORT jint JNICALL Java_app_svan_NativeEngine_nativeLoadParametricPreset(
    JNIEnv* env, jclass, jlong h, jstring text) {
  const char* chars = env->GetStringUTFChars(text, nullptr);
  const ParametricPreset p = parseParametricEq(chars);
  env->ReleaseStringUTFChars(text, chars);
  fromHandle(h)->setBandsAllChannels(p.bands);
  fromHandle(h)->setPreampDb(p.preampDb);
  return static_cast<jint>(p.bands.size());
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetPreamp(JNIEnv*, jclass, jlong h,
                                                                           jdouble db) {
  fromHandle(h)->setPreampDb(db);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetAutoHeadroom(JNIEnv*, jclass, jlong h, jboolean enabled) {
  fromHandle(h)->setAutoHeadroom(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetGainProtection(JNIEnv*, jclass, jlong h, jboolean enabled) {
  fromHandle(h)->setGainProtection(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeResetGainProtection(JNIEnv*, jclass, jlong h) {
  fromHandle(h)->resetGainProtection();
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetSpatialMode(JNIEnv*, jclass, jlong h, jint mode) {
  fromHandle(h)->setSpatialMode(mode);
}
JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetSpatialLoadLimited(JNIEnv*, jclass, jlong h, jboolean on) {
  fromHandle(h)->setSpatialLoadLimited(on == JNI_TRUE);
}
JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeDetailedMix(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->detailedMix();
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeProcess(
    JNIEnv* env, jclass, jlong h, jfloatArray in, jfloatArray out, jint frames) {
  if (h == 0 || frames <= 0) return;  // closed engine: leave the buffer untouched rather than crash
  // Critical access avoids copies on the audio thread. No JNI calls in between.
  auto* src = static_cast<float*>(env->GetPrimitiveArrayCritical(in, nullptr));
  auto* dst = static_cast<float*>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (src && dst) fromHandle(h)->process(src, dst, frames);
  if (dst) env->ReleasePrimitiveArrayCritical(out, dst, 0);
  if (src) env->ReleasePrimitiveArrayCritical(in, src, JNI_ABORT);
}

JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeResponseDb(
    JNIEnv* env, jclass, jlong h, jint channel, jdoubleArray freqs) {
  const jsize n = env->GetArrayLength(freqs);
  std::vector<jdouble> f(n), r(n);
  env->GetDoubleArrayRegion(freqs, 0, n, f.data());
  for (jsize i = 0; i < n; ++i) r[i] = fromHandle(h)->responseDb(channel, f[i]);
  jdoubleArray result = env->NewDoubleArray(n);
  env->SetDoubleArrayRegion(result, 0, n, r.data());
  return result;
}

JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeCurveDb(
    JNIEnv* env, jclass, jlong h, jint channel, jdoubleArray freqs) {
  const jsize n = env->GetArrayLength(freqs);
  std::vector<jdouble> f(n), r(n);
  env->GetDoubleArrayRegion(freqs, 0, n, f.data());
  for (jsize i = 0; i < n; ++i) r[i] = fromHandle(h)->eqResponseDb(channel, f[i]);
  jdoubleArray result = env->NewDoubleArray(n);
  env->SetDoubleArrayRegion(result, 0, n, r.data());
  return result;
}

JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeAppliedGainDb(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->appliedGainDb();
}

JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeGainProtectionDb(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->gainProtectionDb();
}

JNIEXPORT jlong JNICALL Java_app_svan_NativeEngine_nativeCreateCustom(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint oversample, jdouble stopbandDb, jint ditherBits,
    jint ditherMode, jboolean autoHeadroom, jboolean gainProtection) {
  EngineConfig c;
  c.truePeak = true;
  c.sampleRate = sampleRate;
  c.channels = channels;
  c.oversample = oversample;
  c.stopbandDb = stopbandDb;
  c.ditherBits = ditherBits;
  c.ditherMode = static_cast<DitherMode>(ditherMode < 0 || ditherMode > 2 ? 1 : ditherMode);
  c.autoHeadroom = autoHeadroom;
  c.gainProtection = gainProtection;
  return reinterpret_cast<jlong>(new Engine(c));
}

// Same as nativeCreateCustom plus the Detailed (streaming spatial-residual) mode: Backing vocals and Binaural run
// in the WOLA processor and the engine's latency grows by exactly N frames (see nativeLatency). Stereo only.
JNIEXPORT jlong JNICALL Java_app_svan_NativeEngine_nativeCreateDetailed(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint oversample, jdouble stopbandDb, jint ditherBits,
    jint ditherMode, jboolean autoHeadroom, jboolean gainProtection, jboolean spatialResidual) {
  EngineConfig c;
  c.truePeak = true;
  c.sampleRate = sampleRate;
  c.channels = channels;
  c.oversample = oversample;
  c.stopbandDb = stopbandDb;
  c.ditherBits = ditherBits;
  c.ditherMode = static_cast<DitherMode>(ditherMode < 0 || ditherMode > 2 ? 1 : ditherMode);
  c.autoHeadroom = autoHeadroom;
  c.gainProtection = gainProtection;
  c.spatialResidual = spatialResidual == JNI_TRUE;
  return reinterpret_cast<jlong>(new Engine(c));
}

// Returns [cut70, cut110, cut180, cut280 (dB, <= 0), noteHz (0 = no validated note)].
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeBassUnmaskDiagnostics(JNIEnv* env, jclass, jlong h) {
  const Engine* e = fromHandle(h);
  const auto cuts = e->bassUnmaskCutsDb();
  const jdouble out[5] = {cuts[0], cuts[1], cuts[2], cuts[3], e->bassUnmaskNoteHz()};
  jdoubleArray result = env->NewDoubleArray(5);
  env->SetDoubleArrayRegion(result, 0, 5, out);
  return result;
}

// Live Lab readouts, dB: [analogTop (<= 0), expression (signed), shrill 4 kHz presence (<= 0), shrill 8 kHz sizzle (<= 0),
// bass attack lift (>= 0), bass sustain lift (>= 0)]. Zero when the processor is off or idle.
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeProcessorReadouts(JNIEnv* env, jclass, jlong h) {
  const Engine* e = fromHandle(h);
  const auto shrill = e->shrillReductionsDb();
  const auto lift = e->bassDetailLiftDb();
  const jdouble out[6] = {e->analogTopReductionDb(), e->expressionGainDb(), shrill[0], shrill[1], lift[0], lift[1]};
  jdoubleArray result = env->NewDoubleArray(6);
  env->SetDoubleArrayRegion(result, 0, 6, out);
  return result;
}

// Fills `out` (length >= 10) without allocating, so the capture thread can poll it: the six values above, then the four
// selective dynamic EQ reductions (120, 330, 3000, 6500 Hz; <= 0 dB).
JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeProcessorReadoutsInto(JNIEnv* env, jclass, jlong h, jdoubleArray out) {
  if (out == nullptr || env->GetArrayLength(out) < 10) return;
  const Engine* e = fromHandle(h);
  const auto shrill = e->shrillReductionsDb();
  const auto lift = e->bassDetailLiftDb();
  const auto dyn = e->dynamicReductionsDb();
  const jdouble v[10] = {e->analogTopReductionDb(), e->expressionGainDb(), shrill[0], shrill[1], lift[0], lift[1],
                         dyn[0], dyn[1], dyn[2], dyn[3]};
  env->SetDoubleArrayRegion(out, 0, 10, v);
}

// The Svaresa/Svaramanas rule registry as JSON (read-only, for the "How Svaresa decides" screen).
JNIEXPORT jstring JNICALL Java_app_svan_NativeEngine_nativePolicyRulesJson(JNIEnv* env, jclass) {
  return env->NewStringUTF(eqcore::policy::rulesJson().c_str());
}

// Returns [preampDb, type0, freq0, gain0, q0, enabled0, type1, ...]; types use the
// same ordinals as typeFromInt(). Lines that fail to parse are skipped.
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeParseParametric(JNIEnv* env, jclass,
                                                                                jstring text) {
  const char* chars = env->GetStringUTFChars(text, nullptr);
  const ParametricPreset p = parseParametricEq(chars);
  env->ReleaseStringUTFChars(text, chars);
  std::vector<jdouble> out{p.preampDb};
  for (const auto& b : p.bands) {
    out.push_back(static_cast<double>(static_cast<int>(b.type)));
    out.push_back(b.freqHz);
    out.push_back(b.gainDb);
    out.push_back(b.q);
    out.push_back(b.enabled ? 1.0 : 0.0);
  }
  jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
  env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
  return result;
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBassCharacter(JNIEnv*, jclass, jlong h,
                                                                         jdouble character, jdouble crossoverHz) {
  fromHandle(h)->setBassCharacter(character, crossoverHz);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBassResolve(JNIEnv*, jclass, jlong h, jdouble resolve) {
  fromHandle(h)->setBassResolve(resolve);
}

// Selective bass unmasking: 0 = off (default, bit-exact). Not exposed in the UI until validated on music.
JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBassUnmask(JNIEnv*, jclass, jlong h, jdouble amount) {
  fromHandle(h)->setBassUnmask(amount);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetStereoTuner(
    JNIEnv*, jclass, jlong h, jdouble intimacy, jdouble warmth, jdouble smoothness, jdouble space, jdouble instruments, jdouble backingVocals, jdouble spatialDetail) {
  fromHandle(h)->setStereoTuner({intimacy, warmth, smoothness, space, instruments, backingVocals, spatialDetail});
}

// Adds the features just heard to the learned taste. prev may be null. Returns the packed TasteTarget
// (unchanged when the features are not valid or too short).
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeTasteLearn(JNIEnv* env, jclass, jdoubleArray prev,
                                                                           jdoubleArray features) {
  svaramanas::TasteTarget t;
  if (prev) {
    const jsize n = env->GetArrayLength(prev);
    std::vector<jdouble> v(static_cast<size_t>(n));
    if (n) env->GetDoubleArrayRegion(prev, 0, n, v.data());
    t = svaramanas::TasteTarget::unpack(v.data(), n);
  }
  if (features) {
    const jsize n = env->GetArrayLength(features);
    std::vector<jdouble> v(static_cast<size_t>(n));
    if (n) env->GetDoubleArrayRegion(features, 0, n, v.data());
    t = svaramanas::learnTaste(t, SourceFeatures::unpack(v.data(), n));
  }
  double out[svaramanas::TasteTarget::kPacked];
  t.pack(out);
  jdoubleArray res = env->NewDoubleArray(svaramanas::TasteTarget::kPacked);
  env->SetDoubleArrayRegion(res, 0, svaramanas::TasteTarget::kPacked, out);
  return res;
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetGrounding(JNIEnv*, jclass, jlong h, jdouble restraint, jdouble body) {
  fromHandle(h)->setGrounding({restraint, body});
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBassTexture(JNIEnv*, jclass, jlong h, jdouble depth) {
  fromHandle(h)->setBassTexture(depth);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetBassDetail(JNIEnv*, jclass, jlong h, jdouble evenMix, jdouble attack,
                                                                      jdouble spread, jdouble sustain) {
  auto* e = fromHandle(h);
  e->setBassEvenMix(evenMix);
  e->setBassAttack(attack);
  e->setBassSpread(spread);
  e->setBassSustain(sustain);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetExpression(JNIEnv*, jclass, jlong h, jdouble depth) {
  fromHandle(h)->setExpression(depth);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetAnalogTop(JNIEnv*, jclass, jlong h, jdouble depth) {
  fromHandle(h)->setAnalogTop(depth);
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetShrillGuard(JNIEnv*, jclass, jlong h, jdouble depth) {
  fromHandle(h)->setShrillGuard(depth);
}

JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeGroundingRestraintDb(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->groundingRestraintDb();
}

namespace {
// [rmsErrorDb, maxErrorDb, f0, g0, q0, f1, g1, q1, ...]
jdoubleArray packFit(JNIEnv* env, const DenseFit& fit) {
  std::vector<jdouble> out{fit.rmsErrorDb, fit.maxErrorDb};
  for (const auto& b : fit.bands) {
    out.push_back(b.freqHz);
    out.push_back(b.gainDb);
    out.push_back(b.q);
  }
  jdoubleArray r = env->NewDoubleArray(static_cast<jsize>(out.size()));
  env->SetDoubleArrayRegion(r, 0, static_cast<jsize>(out.size()), out.data());
  return r;
}
std::string str(JNIEnv* env, jstring s) {
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c);
  env->ReleaseStringUTFChars(s, c);
  return out;
}
}  // namespace

// Correction (target + taste) - measurement, fitted with bandCount dense bells.
// Returns an empty array if either curve could not be parsed.
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeComputeTuning(
    JNIEnv* env, jclass, jstring measurement, jstring target, jdouble bassDb, jdouble tiltDbPerOct, jint bandCount) {
  const FrCurve m = parseCurve(str(env, measurement));
  const FrCurve t = parseCurve(str(env, target));
  if (m.empty() || t.empty()) return env->NewDoubleArray(0);
  TuningOptions opt;
  opt.bassDb = bassDb;
  opt.tiltDbPerOct = tiltDbPerOct;
  return packFit(env, fitDenseBands(computeCorrection(m, t, opt), bandCount));
}

// Fits a ready-made correction (AutoEq "GraphicEQ: f g; ..." or a plain
// frequency/dB curve) with bandCount dense bells, plus optional taste.
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeFitCorrection(
    JNIEnv* env, jclass, jstring text, jdouble bassDb, jdouble tiltDbPerOct, jint bandCount) {
  const std::string s = str(env, text);
  FrCurve c;
  if (s.find("GraphicEQ") != std::string::npos) {
    for (const auto& [f, g] : parseGraphicEq(s)) {
      c.hz.push_back(f);
      c.db.push_back(g);
    }
  } else {
    c = parseCurve(s);
  }
  if (c.empty()) return env->NewDoubleArray(0);
  // Taste on top of a finished correction: same shapes as computeCorrection's.
  FrCurve flat;
  flat.hz = {20.0, 20000.0};
  flat.db = {0.0, 0.0};
  TuningOptions opt;
  opt.bassDb = bassDb;
  opt.tiltDbPerOct = tiltDbPerOct;
  opt.trebleSmoothFromHz = 20000.0;
  opt.fadeFromHz = 19000.0;
  const FrCurve taste = computeCorrection(flat, flat, opt);
  for (size_t i = 0; i < c.hz.size(); ++i) c.db[i] += taste.at(c.hz[i]);
  return packFit(env, fitDenseBands(c, bandCount));
}

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetAnalysis(JNIEnv*, jclass, jlong h, jboolean on) {
  fromHandle(h)->setAnalysisEnabled(on == JNI_TRUE);
}

// SourceFeatures in its packed layout (SourceFeatures::kPacked doubles).
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeAnalysis(JNIEnv* env, jclass, jlong h) {
  std::vector<jdouble> out(SourceFeatures::kPacked);
  fromHandle(h)->analysis().pack(out.data());
  jdoubleArray r = env->NewDoubleArray(SourceFeatures::kPacked);
  env->SetDoubleArrayRegion(r, 0, SourceFeatures::kPacked, out.data());
  return r;
}

// Svaramanas plan. features: packed SourceFeatures or null (static plan).
// Returns [preamp, predictedDelta, bassChar, intimacy, warmth, smoothness, space, instruments,
//          accepted, rejected, conflictWith, nNotes, notes..., nBands, (type, freq, gain, q)...,
//          groundingRestraint, groundingBody] and, with `withGates`, then the gate list below.
// speakerRoute: output is the phone speaker. taste: packed TasteTarget or null.
// With `withGates`, appends nGates, then (ruleIndex, skipCode) per consulted rule (skipCode 0 = admitted;
// ruleIndex is the position in nativePolicyRulesJson()). `evidence` is null or
// [featuresEpoch, currentEpoch, featuresAgeSeconds, featuresConfidence].
static jdoubleArray planToArray(JNIEnv* env, jdoubleArray features, jint feel, jintArray order, jdouble strength,
                                jboolean stereoEngine, jboolean svaresaMode, jboolean speakerRoute, jdoubleArray taste,
                                jdoubleArray evidence, bool withGates) {
  svaramanas::Request r;
  r.feel = static_cast<svaramanas::Feel>(feel < 0 || feel > 5 ? 0 : feel);
  const jsize n = order ? env->GetArrayLength(order) : 0;
  std::vector<jint> o(static_cast<size_t>(n));
  if (n) env->GetIntArrayRegion(order, 0, n, o.data());
  for (jint b : o) {
    r.order.push_back(static_cast<uint32_t>(b));
    r.categories |= static_cast<uint32_t>(b);
  }
  r.strength = strength;
  r.stereoEngine = stereoEngine == JNI_TRUE;
  r.svaresaMode = svaresaMode == JNI_TRUE;
  if (evidence && env->GetArrayLength(evidence) >= 4) {
    jdouble e[4];
    env->GetDoubleArrayRegion(evidence, 0, 4, e);
    r.featuresEpoch = static_cast<uint64_t>(e[0] < 0 ? 0 : e[0]);
    r.epoch = static_cast<uint64_t>(e[1] < 0 ? 0 : e[1]);
    r.featuresAgeSeconds = e[2];
    r.featuresConfidence = e[3];
  }
  r.speakerRoute = speakerRoute == JNI_TRUE;
  svaramanas::TasteTarget learned;
  if (taste) {
    const jsize tn = env->GetArrayLength(taste);
    std::vector<jdouble> tv(static_cast<size_t>(tn));
    if (tn) env->GetDoubleArrayRegion(taste, 0, tn, tv.data());
    learned = svaramanas::TasteTarget::unpack(tv.data(), tn);
    if (learned.valid) r.taste = &learned;
  }
  SourceFeatures f;
  bool have = false;
  if (features) {
    const jsize fn = env->GetArrayLength(features);
    std::vector<jdouble> fv(static_cast<size_t>(fn));
    env->GetDoubleArrayRegion(features, 0, fn, fv.data());
    f = SourceFeatures::unpack(fv.data(), fn);
    have = true;
  }
  const auto p = svaramanas::plan(r, have ? &f : nullptr);
  std::vector<jdouble> out{p.preampDb, p.predictedDeltaDb, p.bassCharacter, p.stereo.intimacy, p.stereo.warmth,
                           p.stereo.smoothness, p.stereo.space, p.stereo.instruments,
                           static_cast<double>(p.categories.accepted), static_cast<double>(p.categories.rejected),
                           static_cast<double>(p.categories.conflictWith), static_cast<double>(p.notes.size())};
  for (int note : p.notes) out.push_back(note);
  out.push_back(static_cast<double>(p.bands.size()));
  for (const auto& b : p.bands) {
    out.push_back(static_cast<double>(static_cast<int>(b.type)));
    out.push_back(b.freqHz);
    out.push_back(b.gainDb);
    out.push_back(b.q);
  }
  // Appended after the bands so older readers stay valid: Svaresa's grounded voicing (2 values).
  out.push_back(p.grounding.restraint);
  out.push_back(p.grounding.body);
  if (withGates) {
    out.push_back(static_cast<double>(p.gates.size()));
    for (const auto& g : p.gates) {
      out.push_back(static_cast<double>(eqcore::policy::ruleIndex(g.rule)));
      out.push_back(static_cast<double>(eqcore::policy::skipCode(g.skip)));
    }
  }
  jdoubleArray res = env->NewDoubleArray(static_cast<jsize>(out.size()));
  env->SetDoubleArrayRegion(res, 0, static_cast<jsize>(out.size()), out.data());
  return res;
}

JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeSvaramanasPlan(
    JNIEnv* env, jclass, jdoubleArray features, jint feel, jintArray order, jdouble strength, jboolean stereoEngine,
    jboolean svaresaMode, jboolean speakerRoute, jdoubleArray taste) {
  return planToArray(env, features, feel, order, strength, stereoEngine, svaresaMode, speakerRoute, taste, nullptr, false);
}

// Same plan, with evidence identity in and the evidence-gate outcome appended (see planToArray).
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeSvaramanasPlanGated(
    JNIEnv* env, jclass, jdoubleArray features, jint feel, jintArray order, jdouble strength, jboolean stereoEngine,
    jboolean svaresaMode, jboolean speakerRoute, jdoubleArray taste, jdoubleArray evidence) {
  return planToArray(env, features, feel, order, strength, stereoEngine, svaresaMode, speakerRoute, taste, evidence, true);
}

// Human text for a skip code returned by the gated plan ("" for 0 = admitted).
JNIEXPORT jstring JNICALL Java_app_svan_NativeEngine_nativePolicySkipText(JNIEnv* env, jclass, jint code) {
  const int c = code < 0 || code > static_cast<int>(eqcore::policy::Skip::AutoMasterOff) ? 0 : code;
  return env->NewStringUTF(eqcore::policy::skipText(static_cast<eqcore::policy::Skip>(c)));
}

// Match the combined/slewed guide and context curve, not two independent trims.
JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeSmartLoudnessDelta(
    JNIEnv* env, jclass, jdoubleArray bands, jdoubleArray features,
    jdouble intimacy, jdouble space, jdouble instruments) {
  const jsize n = bands ? env->GetArrayLength(bands) : 0;
  std::vector<jdouble> raw(static_cast<size_t>(n));
  if (n) env->GetDoubleArrayRegion(bands, 0, n, raw.data());
  std::vector<BandParams> bs;
  for (jsize i = 0; i + 4 < n; i += 5) {
    const int type = static_cast<int>(raw[i]);
    if (type < 0 || type > static_cast<int>(FilterType::AllPass)) continue;
    bs.push_back({static_cast<FilterType>(type), raw[i + 1], raw[i + 2], raw[i + 3], raw[i + 4] != 0});
  }
  SourceFeatures f;
  if (features) {
    const jsize count = env->GetArrayLength(features);
    std::vector<jdouble> packed(static_cast<size_t>(count));
    if (count) env->GetDoubleArrayRegion(features, 0, count, packed.data());
    f = SourceFeatures::unpack(packed.data(), count);
  }
  const StereoTunerParams stereo{intimacy, 0, 0, space, instruments};
  return svaramanas::predictedGuideLoudnessDeltaDb(bs, stereo, f.valid ? &f : nullptr);
}

JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeOverlapScale(JNIEnv* env, jclass, jdoubleArray bands) {
  const jsize n=env->GetArrayLength(bands);
  std::vector<jdouble> raw(static_cast<size_t>(n));env->GetDoubleArrayRegion(bands,0,n,raw.data());
  std::vector<BandParams> bs;
  for(jsize i=0;i+4<n;i+=5) {
    if (!std::isfinite(raw[i]) || raw[i]<0 || raw[i]>static_cast<int>(FilterType::AllPass)) continue;
    bs.push_back({static_cast<FilterType>(static_cast<int>(raw[i])),raw[i+1],raw[i+2],raw[i+3],raw[i+4]!=0});
  }
  return positiveEqOverlapScale(bs);
}

JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeFitGraphic(JNIEnv* env, jclass, jdoubleArray bands, jint count) {
  const jsize n=env->GetArrayLength(bands);
  std::vector<jdouble> raw(static_cast<size_t>(n));
  env->GetDoubleArrayRegion(bands,0,n,raw.data());
  std::vector<BandParams> bs;
  for(jsize i=0;i+4<n;i+=5) {
    if (!std::isfinite(raw[i]) || raw[i]<0 || raw[i]>static_cast<int>(FilterType::AllPass)) continue;
    bs.push_back({static_cast<FilterType>(static_cast<int>(raw[i])),raw[i+1],raw[i+2],raw[i+3],raw[i+4]!=0});
  }
  const auto fit=fitGraphicEq(bs,count);
  std::vector<jdouble> out{fit.rmsDb,fit.maxDb};
  for(const auto& b:fit.bands) { out.push_back(static_cast<int>(b.type)); out.push_back(b.freqHz); out.push_back(b.gainDb); out.push_back(b.q); }
  auto r=env->NewDoubleArray(static_cast<jsize>(out.size()));
  env->SetDoubleArrayRegion(r,0,static_cast<jsize>(out.size()),out.data()); return r;
}

JNIEXPORT jint JNICALL Java_app_svan_NativeEngine_nativeLatency(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->latencyFrames();
}


JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetDynamicEq(JNIEnv*,jclass,jlong h,jdouble amount) {
 fromHandle(h)->setDynamicEq(amount);
}
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeDynamicReductions(JNIEnv* env,jclass,jlong h) {
 auto r=fromHandle(h)->dynamicReductionsDb();auto out=env->NewDoubleArray(4);env->SetDoubleArrayRegion(out,0,4,r.data());return out;
}
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeCalibratedTuning(JNIEnv* env,jclass,jstring measurement,jstring target,jboolean published,jdouble amount,jint bands,jdouble bass,jdouble tilt) {
 const auto text=str(env,measurement);FrCurve m;
 if(published&&text.find("GraphicEQ")!=std::string::npos)for(const auto& [f,g]:parseGraphicEq(text)){m.hz.push_back(f);m.db.push_back(g);}
 else m=parseCurve(text);
 auto result=published?calibratedProfile(m,amount,bands,bass,tilt):calibratedTuning(m,parseCurve(str(env,target)),amount,bands,bass,tilt);
 if(!result.valid)return env->NewDoubleArray(0);
 std::vector<double> out={result.fit.rmsErrorDb,result.fit.maxErrorDb,result.lowHz,result.highHz};
 for(const auto& b:result.fit.bands){out.push_back(b.freqHz);out.push_back(b.gainDb);out.push_back(b.q);}
 auto r=env->NewDoubleArray(out.size());env->SetDoubleArrayRegion(r,0,out.size(),out.data());return r;
}
JNIEXPORT jdoubleArray JNICALL Java_app_svan_NativeEngine_nativeMatchComparison(JNIEnv* env,jclass,jfloatArray a,jfloatArray b,jint fs) {
 auto n=env->GetArrayLength(a);if(fs<16000||fs>96000||n!=env->GetArrayLength(b)||n>fs*2*15||n<fs*2*4)return env->NewDoubleArray(0);
 std::vector<float> x(n),y(n);env->GetFloatArrayRegion(a,0,n,x.data());env->GetFloatArrayRegion(b,0,n,y.data());std::array<double,6> levels{};
 if(!matchComparison(x,y,fs,levels))return env->NewDoubleArray(0);
 env->SetFloatArrayRegion(a,0,n,x.data());env->SetFloatArrayRegion(b,0,n,y.data());auto r=env->NewDoubleArray(6);env->SetDoubleArrayRegion(r,0,6,levels.data());return r;
}
JNIEXPORT jdouble JNICALL Java_app_svan_NativeEngine_nativeReconstructedPeak(JNIEnv* env,jclass,jfloatArray audio,jint fs) {
 auto n=env->GetArrayLength(audio);if(n>fs*2*15||fs<16000||fs>96000)return -1;
 std::vector<float> samples(n);env->GetFloatArrayRegion(audio,0,n,samples.data());
 OversamplerSpec spec;spec.factor=8;spec.baseSampleRate=fs;spec.passbandHz=fs*.45;spec.stopbandDb=140;spec.maxBlock=512;
 std::vector<double> block(512),high(4096);double peak=0;
 for(int ch=0;ch<2;++ch){Oversampler up(spec);for(int start=0;start<n/2;start+=512){int count=std::min(512,n/2-start);for(int k=0;k<count;++k)block[k]=samples[(start+k)*2+ch];up.up(block.data(),count,high.data());for(int k=0;k<count*8;++k)peak=std::max(peak,std::abs(high[k]));}}
 return peak;
}
}  // extern "C"

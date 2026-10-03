// JNI surface for app.svan.NativeEngine.
#include <jni.h>

#include <string>
#include <vector>

#include "eqcore/autoeq.h"
#include "eqcore/engine.h"
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

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeProcess(
    JNIEnv* env, jclass, jlong h, jfloatArray in, jfloatArray out, jint frames) {
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

JNIEXPORT void JNICALL Java_app_svan_NativeEngine_nativeSetStereoTuner(
    JNIEnv*, jclass, jlong h, jdouble intimacy, jdouble warmth, jdouble smoothness, jdouble space, jdouble instruments) {
  fromHandle(h)->setStereoTuner({intimacy, warmth, smoothness, space, instruments});
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

JNIEXPORT jint JNICALL Java_app_svan_NativeEngine_nativeLatency(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->latencyFrames();
}

}  // extern "C"

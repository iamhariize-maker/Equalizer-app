// JNI surface for dev.equalizer.app.NativeEngine.
#include <jni.h>

#include <string>
#include <vector>

#include "eqcore/autoeq.h"
#include "eqcore/engine.h"

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

JNIEXPORT jlong JNICALL Java_dev_equalizer_app_NativeEngine_nativeCreate(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint quality, jint outputBits) {
  const auto mode = static_cast<QualityMode>(quality < 0 || quality > 3 ? 0 : quality);
  auto cfg = EngineConfig::forQuality(mode, sampleRate, channels, outputBits);
  return reinterpret_cast<jlong>(new Engine(cfg));
}

JNIEXPORT void JNICALL Java_dev_equalizer_app_NativeEngine_nativeDestroy(JNIEnv*, jclass, jlong h) {
  delete fromHandle(h);
}

JNIEXPORT void JNICALL Java_dev_equalizer_app_NativeEngine_nativeSetBands(
    JNIEnv* env, jclass, jlong h, jint channel, jintArray types, jdoubleArray freqs,
    jdoubleArray gains, jdoubleArray qs) {
  const jsize n = env->GetArrayLength(types);
  if (env->GetArrayLength(freqs) != n || env->GetArrayLength(gains) != n ||
      env->GetArrayLength(qs) != n)
    return;
  std::vector<jint> t(n);
  std::vector<jdouble> f(n), g(n), q(n);
  env->GetIntArrayRegion(types, 0, n, t.data());
  env->GetDoubleArrayRegion(freqs, 0, n, f.data());
  env->GetDoubleArrayRegion(gains, 0, n, g.data());
  env->GetDoubleArrayRegion(qs, 0, n, q.data());
  std::vector<BandParams> bands(n);
  for (jsize i = 0; i < n; ++i) bands[i] = {typeFromInt(t[i]), f[i], g[i], q[i], true};
  if (channel < 0) fromHandle(h)->setBandsAllChannels(bands);
  else fromHandle(h)->setBands(channel, bands);
}

JNIEXPORT jint JNICALL Java_dev_equalizer_app_NativeEngine_nativeLoadParametricPreset(
    JNIEnv* env, jclass, jlong h, jstring text) {
  const char* chars = env->GetStringUTFChars(text, nullptr);
  const ParametricPreset p = parseParametricEq(chars);
  env->ReleaseStringUTFChars(text, chars);
  fromHandle(h)->setBandsAllChannels(p.bands);
  fromHandle(h)->setPreampDb(p.preampDb);
  return static_cast<jint>(p.bands.size());
}

JNIEXPORT void JNICALL Java_dev_equalizer_app_NativeEngine_nativeSetPreamp(JNIEnv*, jclass, jlong h,
                                                                           jdouble db) {
  fromHandle(h)->setPreampDb(db);
}

JNIEXPORT void JNICALL Java_dev_equalizer_app_NativeEngine_nativeProcess(
    JNIEnv* env, jclass, jlong h, jfloatArray in, jfloatArray out, jint frames) {
  // Critical access avoids copies on the audio thread. No JNI calls in between.
  auto* src = static_cast<float*>(env->GetPrimitiveArrayCritical(in, nullptr));
  auto* dst = static_cast<float*>(env->GetPrimitiveArrayCritical(out, nullptr));
  if (src && dst) fromHandle(h)->process(src, dst, frames);
  if (dst) env->ReleasePrimitiveArrayCritical(out, dst, 0);
  if (src) env->ReleasePrimitiveArrayCritical(in, src, JNI_ABORT);
}

JNIEXPORT jdoubleArray JNICALL Java_dev_equalizer_app_NativeEngine_nativeResponseDb(
    JNIEnv* env, jclass, jlong h, jint channel, jdoubleArray freqs) {
  const jsize n = env->GetArrayLength(freqs);
  std::vector<jdouble> f(n), r(n);
  env->GetDoubleArrayRegion(freqs, 0, n, f.data());
  for (jsize i = 0; i < n; ++i) r[i] = fromHandle(h)->responseDb(channel, f[i]);
  jdoubleArray result = env->NewDoubleArray(n);
  env->SetDoubleArrayRegion(result, 0, n, r.data());
  return result;
}

JNIEXPORT jint JNICALL Java_dev_equalizer_app_NativeEngine_nativeLatency(JNIEnv*, jclass, jlong h) {
  return fromHandle(h)->latencyFrames();
}

}  // extern "C"

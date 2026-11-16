#include <jni.h>

#include "engine.h"

static gp::Engine& eng() {
    static gp::Engine e;
    return e;
}

#define JNI(name) Java_com_gpaudio_gpmix_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jint JNICALL JNI(start)(JNIEnv*, jobject, jint dev) { return eng().start(dev); }
JNIEXPORT void JNICALL JNI(stop)(JNIEnv*, jobject) { eng().stop(); }
JNIEXPORT void JNICALL JNI(setParam)(JNIEnv*, jobject, jint ch, jint id, jfloat v) { eng().setParam(ch, id, v); }
JNIEXPORT jfloat JNICALL JNI(getMeter)(JNIEnv*, jobject, jint ch) { return eng().getMeter(ch); }

JNIEXPORT void JNICALL JNI(loadPad)(JNIEnv* env, jobject, jint idx, jfloatArray data, jint channels, jint rate) {
    if (channels < 1) return;
    jfloat* d = env->GetFloatArrayElements(data, nullptr);
    const jsize len = env->GetArrayLength(data);
    eng().loadPad(idx, d, len / channels, channels, rate);
    env->ReleaseFloatArrayElements(data, d, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI(clearPad)(JNIEnv*, jobject, jint idx) { eng().clearPad(idx); }
JNIEXPORT void JNICALL JNI(triggerPad)(JNIEnv*, jobject, jint idx) { eng().triggerPad(idx); }
JNIEXPORT void JNICALL JNI(stopPads)(JNIEnv*, jobject) { eng().stopPads(); }
JNIEXPORT jboolean JNICALL JNI(isPadPlaying)(JNIEnv*, jobject, jint idx) { return eng().isPadPlaying(idx); }
JNIEXPORT jfloat JNICALL JNI(latencyMs)(JNIEnv*, jobject) { return eng().latencyMs(); }

}  // extern "C"

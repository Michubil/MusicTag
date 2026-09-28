#include <jni.h>
#include <cstdint>
#include "chromaprint.h"

namespace {
ChromaprintContext *context(jlong handle) {
    return reinterpret_cast<ChromaprintContext *>(static_cast<intptr_t>(handle));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_michubil_musictag_data_fingerprint_NativeFingerprint_create(
    JNIEnv *, jobject, jint sample_rate, jint channels) {
    auto *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!ctx) return 0;
    if (!chromaprint_start(ctx, sample_rate, channels)) {
        chromaprint_free(ctx);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_michubil_musictag_data_fingerprint_NativeFingerprint_feed(
    JNIEnv *env, jobject, jlong handle, jobject buffer, jint offset, jint size) {
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    if (!handle || !data || offset < 0 || offset % 2 != 0 || size < 0 || size % 2 != 0 ||
        offset > capacity || size > capacity - offset) return JNI_FALSE;
    return chromaprint_feed(context(handle),
        reinterpret_cast<const int16_t *>(data + offset), size / 2) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_michubil_musictag_data_fingerprint_NativeFingerprint_finish(
    JNIEnv *env, jobject, jlong handle) {
    if (!handle || !chromaprint_finish(context(handle))) return nullptr;
    char *fingerprint = nullptr;
    if (!chromaprint_get_fingerprint(context(handle), &fingerprint) || !fingerprint) return nullptr;
    auto *result = env->NewStringUTF(fingerprint);
    chromaprint_dealloc(fingerprint);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_top_michubil_musictag_data_fingerprint_NativeFingerprint_release(
    JNIEnv *, jobject, jlong handle) {
    if (handle) chromaprint_free(context(handle));
}

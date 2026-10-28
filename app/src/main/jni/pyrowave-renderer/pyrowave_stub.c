// PyroWave needs Vulkan 1.3, which the 32-bit ABIs never get: Delos reports it as unavailable there.
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeIsAvailable(JNIEnv *env, jclass clazz) {
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeCreate(JNIEnv *env, jclass clazz, jobject surface, jint width,
                                                               jint height, jint frameRate, jint outputWidth, jint outputHeight, jboolean chroma444,
                                                               jboolean fullRange, jint matrix, jboolean tenBit,
                                                               jint upscalingMode, jint supersamplingLevel, jfloat sharpness) {
    return 0;
}

JNIEXPORT void JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetUpscalingMode(JNIEnv *env, jclass clazz, jlong handle, jint mode) {}
JNIEXPORT jint JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetUpscalingMode(JNIEnv *env, jclass clazz, jlong handle) { return 0; }
JNIEXPORT void JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetSharpness(JNIEnv *env, jclass clazz, jlong handle, jfloat sharpness) {}
JNIEXPORT void JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetSupersamplingLevel(JNIEnv *env, jclass clazz, jlong handle, jint level) {}
JNIEXPORT jboolean JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeIsSupersampling(JNIEnv *env, jclass clazz, jlong handle) { return JNI_FALSE; }
JNIEXPORT jstring JNICALL Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetScaleDescription(JNIEnv *env, jclass clazz, jlong handle) { return NULL; }

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetLastGpuDecodeUs(JNIEnv *env, jclass clazz, jlong handle) {
    return 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetHdrMode(JNIEnv *env, jclass clazz, jlong handle, jboolean enabled) {
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeTakeStats(JNIEnv *env, jclass clazz, jlongArray out) {
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
}

// Delos: EGL_ANDROID_get_frame_timestamps for the SGSR output on a GLES window surface (the SDR path). It reads
// when the GPU finished a frame, when SurfaceFlinger latched it and when the display presented it, a few frames
// after the swap. Everything runs on the SGSR GL thread with its context current; nothing here reads pixels.
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <cstring>
#include <unistd.h>
#include <GLES3/gl3.h>
#include "sgsr_sync.h"

namespace {
PFNEGLGETNEXTFRAMEIDANDROIDPROC nextFrameId=nullptr;
PFNEGLGETFRAMETIMESTAMPSANDROIDPROC frameTimestamps=nullptr;
// The timestamps asked for, and which of the three results each one fills (-1 for the ones the surface lacks)
constexpr EGLint kWanted[3]={EGL_RENDERING_COMPLETE_TIME_ANDROID,EGL_COMPOSITION_LATCH_TIME_ANDROID,EGL_DISPLAY_PRESENT_TIME_ANDROID};
EGLint names[3];
int slots[3];
int nameCount=0;
}

// Turns timestamp collection on for the current draw surface. Returns false when the platform lacks it.
extern "C" JNIEXPORT jboolean JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeFrameTimestampsEnable(JNIEnv *,jclass){
    EGLDisplay display=eglGetCurrentDisplay();
    EGLSurface surface=eglGetCurrentSurface(EGL_DRAW);
    if(display==EGL_NO_DISPLAY||surface==EGL_NO_SURFACE) return JNI_FALSE;
    const char *extensions=eglQueryString(display,EGL_EXTENSIONS);
    if(!extensions||!strstr(extensions,"EGL_ANDROID_get_frame_timestamps")) return JNI_FALSE;
    nextFrameId=reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC>(eglGetProcAddress("eglGetNextFrameIdANDROID"));
    frameTimestamps=reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC>(eglGetProcAddress("eglGetFrameTimestampsANDROID"));
    auto supported=reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROIDPROC>(eglGetProcAddress("eglGetFrameTimestampSupportedANDROID"));
    if(!nextFrameId||!frameTimestamps||!supported) return JNI_FALSE;
    if(!eglSurfaceAttrib(display,surface,EGL_TIMESTAMPS_ANDROID,EGL_TRUE)) return JNI_FALSE;
    nameCount=0;
    for(int i=0;i<3;i++){
        slots[i]=-1;
        if(supported(display,surface,kWanted[i])){names[nameCount]=kWanted[i];slots[i]=nameCount++;}
    }
    __android_log_print(ANDROID_LOG_INFO,"SGSR","EGL frame timestamps: gpu %d latch %d present %d",slots[0]>=0,slots[1]>=0,slots[2]>=0);
    return nameCount>0?JNI_TRUE:JNI_FALSE;
}

// The id the next eglSwapBuffers will have, to be called just before it; -1 when unavailable.
extern "C" JNIEXPORT jlong JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeFrameNextId(JNIEnv *,jclass){
    EGLuint64KHR id=0;
    if(!nextFrameId||!nextFrameId(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),&id)) return -1;
    return jlong(id);
}

// The CLOCK_MONOTONIC times (ns) of a swapped frame: out[0] GPU done, out[1] SurfaceFlinger latch, out[2] display
// present, -1 for one that is not available. Returns 0 when all are known, 1 while one is still pending and -1 when
// the frame is no longer remembered by the platform.
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeFrameTimestamps(JNIEnv *env,jclass,jlong frameId,jlongArray out){
    if(!frameTimestamps||nameCount==0) return -1;
    EGLnsecsANDROID values[3]={};
    if(!frameTimestamps(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),EGLuint64KHR(frameId),nameCount,names,values)) return -1;
    jlong result[3];
    for(int i=0;i<3;i++){
        const EGLnsecsANDROID value=slots[i]>=0?values[slots[i]]:EGL_TIMESTAMP_INVALID_ANDROID;
        if(value==EGL_TIMESTAMP_PENDING_ANDROID) return 1;
        result[i]=value>0?jlong(value):-1;
    }
    env->SetLongArrayRegion(out,0,3,result);
    return 0;
}

// ---- GPU fences for the SDR path: bounding the frames the GPU has in flight ----------------------------------------
namespace {
PFNEGLCREATESYNCKHRPROC createSync=nullptr;
PFNEGLDESTROYSYNCKHRPROC destroySync=nullptr;
PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupFence=nullptr;
bool fencesChecked=false,fencesAvailable=false;
}

// Flushes the commands queued so far and returns a native fence (a sync file descriptor, owned by the caller, closed
// with nativeGpuFenceClose) that signals when the GPU has executed them. -2: nothing was pending, the work is already
// done. -1: the driver cannot make native fences.
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeGpuFenceCreate(JNIEnv *,jclass){
    EGLDisplay display=eglGetCurrentDisplay();
    if(display==EGL_NO_DISPLAY) return -1;
    if(!fencesChecked){
        fencesChecked=true;
        const char *extensions=eglQueryString(display,EGL_EXTENSIONS);
        createSync=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
        destroySync=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
        dupFence=reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
        fencesAvailable=extensions&&strstr(extensions,"EGL_ANDROID_native_fence_sync")&&strstr(extensions,"EGL_KHR_fence_sync")
                &&createSync&&destroySync&&dupFence;
        __android_log_print(ANDROID_LOG_INFO,"SGSR","GPU fences for the in-flight bound: %s",fencesAvailable?"native":"unavailable");
    }
    if(!fencesAvailable) return -1;
    const EGLint attributes[]={EGL_SYNC_NATIVE_FENCE_FD_ANDROID,EGL_NO_NATIVE_FENCE_FD_ANDROID,EGL_NONE};
    EGLSyncKHR sync=createSync(display,EGL_SYNC_NATIVE_FENCE_ANDROID,attributes);
    if(sync==EGL_NO_SYNC_KHR) return -1;
    // The native fence exists once the commands are flushed
    glFlush();
    const int fd=dupFence(display,sync);
    destroySync(display,sync);
    return fd>=0?fd:-2;
}

// -1 while the fence has not signalled, otherwise its signal time (CLOCK_MONOTONIC ns), 0 when the kernel gives none.
extern "C" JNIEXPORT jlong JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeGpuFenceStatus(JNIEnv *,jclass,jint fd){
    if(!sgsr_sync::signalled(fd)) return -1;
    const int64_t time=sgsr_sync::fenceSignalNs(fd);
    return time>0?jlong(time):0;
}

extern "C" JNIEXPORT void JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeGpuFenceClose(JNIEnv *,jclass,jint fd){
    if(fd>=0) close(fd);
}

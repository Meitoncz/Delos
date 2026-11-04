// Delos: the GPU half of the PyroWave P010 HDR output (see "YUV output" in pyrowave_renderer.cpp).
//
// Vulkan cannot write P010 AHardwareBuffers on Adreno (it imports them only as a sample-only external
// format), but OpenGL ES can render into them with GL_EXT_YUV_target. Vulkan fills an RGBA16F
// AHardwareBuffer with each pixel's (Y, Cb, Cr) as 10-bit code values; this class copies it into the P010
// targets. It lives on the thread that draws, which keeps its context current.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>

#include <vector>

class YuvTargetGl {
public:
    ~YuvTargetGl() { destroy(); }

    // False when the device lacks an extension the copy needs; destroy() then cleans up
    bool create(AHardwareBuffer *source, AHardwareBuffer *const *targets, int targetCount, int width, int height);

    // Waits until the previous copy no longer reads the source, before Vulkan writes it again
    void waitSourceFree();

    // Copies the source into target [index]; returns the fence fd that signals when the copy is done
    // (-1 when there is none), or -2 on failure
    int draw(int index);

    // One-shot ownership transfer after launch arrival; ordinary frames keep the context current.
    bool releaseThread();
    bool acquireThread();

    void destroy();

private:
    struct Target {
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint texture = 0;
        GLuint framebuffer = 0;
        int width = 0;
        int height = 0;
    };

    bool loadExtensions();
    EGLImageKHR importBuffer(AHardwareBuffer *buffer);

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE;
    GLuint program = 0;
    EGLImageKHR sourceImage = EGL_NO_IMAGE_KHR;
    GLuint sourceTexture = 0;
    std::vector<Target> targets;
    EGLSyncKHR lastCopy = EGL_NO_SYNC_KHR;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNEGLCREATESYNCKHRPROC createSync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroySync = nullptr;
    PFNEGLCLIENTWAITSYNCKHRPROC clientWaitSync = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupNativeFence = nullptr;
    void (*imageTargetTexture)(GLenum, void *) = nullptr;
};

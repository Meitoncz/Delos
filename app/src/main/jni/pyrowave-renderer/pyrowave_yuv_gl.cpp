// Delos: see pyrowave_yuv_gl.h.
#include "pyrowave_yuv_gl.h"

#include <GLES2/gl2ext.h>
#include <android/log.h>

#include <cstring>

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    // One triangle covering the target
    const char *const VERTEX_SHADER = R"(#version 300 es
void main() {
    vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
)";

    // Writes the source's (Y, Cb, Cr) unconverted: the target is YUV. Each 2x2 block carries one chroma
    // value in the source, so the driver's choice of which pixel feeds the subsampled chroma is irrelevant.
    const char *const FRAGMENT_SHADER = R"(#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform highp sampler2D u_source;
layout(yuv) out vec4 color;
void main() {
    color = vec4(texelFetch(u_source, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
}
)";

    bool hasExtension(const char *list, const char *name) {
        if (list == nullptr) {
            return false;
        }
        const size_t length = std::strlen(name);
        for (const char *p = std::strstr(list, name); p != nullptr; p = std::strstr(p + length, name)) {
            if ((p == list || p[-1] == ' ') && (p[length] == ' ' || p[length] == '\0')) {
                return true;
            }
        }
        return false;
    }

    GLuint compile(GLenum type, const char *source) {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint ok = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512] = {};
            glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
            LOGE("YUV copy shader: %s", log);
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}

bool YuvTargetGl::loadExtensions() {
    const char *egl = eglQueryString(display, EGL_EXTENSIONS);
    for (const char *name : {"EGL_ANDROID_get_native_client_buffer", "EGL_ANDROID_image_native_buffer",
                             "EGL_KHR_image_base", "EGL_ANDROID_native_fence_sync", "EGL_KHR_fence_sync"}) {
        if (!hasExtension(egl, name)) {
            LOGI("YUV copy: no %s", name);
            return false;
        }
    }
    getNativeClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    createSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    clientWaitSync = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    dupNativeFence = reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    imageTargetTexture = reinterpret_cast<void (*)(GLenum, void *)>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    return getNativeClientBuffer != nullptr && createImage != nullptr && destroyImage != nullptr &&
           createSync != nullptr && destroySync != nullptr && clientWaitSync != nullptr &&
           dupNativeFence != nullptr && imageTargetTexture != nullptr;
}

EGLImageKHR YuvTargetGl::importBuffer(AHardwareBuffer *buffer) {
    EGLClientBuffer client = getNativeClientBuffer(buffer);
    if (client == nullptr) {
        return EGL_NO_IMAGE_KHR;
    }
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    return createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, attributes);
}

bool YuvTargetGl::create(AHardwareBuffer *source, AHardwareBuffer *const *targetBuffers, int targetCount,
                         int width, int height) {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr) || !loadExtensions()) {
        return false;
    }
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                       EGL_NONE};
    EGLConfig config = nullptr;
    EGLint configs = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &configs) || configs < 1) {
        LOGE("YUV copy: no ES 3 config");
        return false;
    }
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (context == EGL_NO_CONTEXT) {
        LOGE("YUV copy: no ES 3 context");
        return false;
    }
    // A 1x1 pbuffer unless surfaceless contexts work; all drawing goes to framebuffer objects
    if (!hasExtension(eglQueryString(display, EGL_EXTENSIONS), "EGL_KHR_surfaceless_context")) {
        const EGLint pbufferAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        pbuffer = eglCreatePbufferSurface(display, config, pbufferAttributes);
    }
    if (!eglMakeCurrent(display, pbuffer, pbuffer, context)) {
        LOGE("YUV copy: cannot make the context current");
        return false;
    }
    if (!hasExtension(reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS)), "GL_EXT_YUV_target")) {
        LOGI("YUV copy: no GL_EXT_YUV_target");
        return false;
    }

    GLuint vertex = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (vertex == 0 || fragment == 0) {
        return false;
    }
    program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("YUV copy: the program does not link");
        return false;
    }
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "u_source"), 0);

    sourceImage = importBuffer(source);
    if (sourceImage == EGL_NO_IMAGE_KHR) {
        LOGE("YUV copy: cannot import the source");
        return false;
    }
    glGenTextures(1, &sourceTexture);
    glBindTexture(GL_TEXTURE_2D, sourceTexture);
    imageTargetTexture(GL_TEXTURE_2D, sourceImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    for (int i = 0; i < targetCount; ++i) {
        Target target;
        target.width = width;
        target.height = height;
        target.image = importBuffer(targetBuffers[i]);
        if (target.image == EGL_NO_IMAGE_KHR) {
            LOGE("YUV copy: cannot import a P010 target");
            return false;
        }
        glGenTextures(1, &target.texture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, target.texture);
        imageTargetTexture(GL_TEXTURE_EXTERNAL_OES, target.image);
        glGenFramebuffers(1, &target.framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_EXTERNAL_OES, target.texture, 0);
        targets.push_back(target);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("YUV copy: a P010 target is not renderable");
            return false;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return glGetError() == GL_NO_ERROR;
}

void YuvTargetGl::waitSourceFree() {
    if (lastCopy != EGL_NO_SYNC_KHR) {
        clientWaitSync(display, lastCopy, 0, 100'000'000);
        destroySync(display, lastCopy);
        lastCopy = EGL_NO_SYNC_KHR;
    }
}

int YuvTargetGl::draw(int index) {
    const Target &target = targets[size_t(index)];
    glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
    glViewport(0, 0, target.width, target.height);
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sourceTexture);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    const EGLint attributes[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
    lastCopy = createSync(display, EGL_SYNC_NATIVE_FENCE_ANDROID, attributes);
    // The native fence exists once the commands are flushed
    glFlush();
    if (lastCopy == EGL_NO_SYNC_KHR) {
        glFinish();
        return -1;
    }
    const int fd = dupNativeFence(display, lastCopy);
    return fd >= 0 ? fd : -1;
}

bool YuvTargetGl::releaseThread() {
    return context == EGL_NO_CONTEXT ||
           eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
}

bool YuvTargetGl::acquireThread() {
    return context == EGL_NO_CONTEXT || eglMakeCurrent(display, pbuffer, pbuffer, context) == EGL_TRUE;
}

void YuvTargetGl::destroy() {
    if (display == EGL_NO_DISPLAY) {
        return;
    }
    // GL objects go with the context; delete them only where it is current
    if (context != EGL_NO_CONTEXT && eglGetCurrentContext() == context) {
        for (auto &target : targets) {
            glDeleteFramebuffers(1, &target.framebuffer);
            glDeleteTextures(1, &target.texture);
        }
        if (sourceTexture != 0) glDeleteTextures(1, &sourceTexture);
        if (program != 0) glDeleteProgram(program);
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (lastCopy != EGL_NO_SYNC_KHR) destroySync(display, lastCopy);
    for (auto &target : targets) {
        if (target.image != EGL_NO_IMAGE_KHR) destroyImage(display, target.image);
    }
    if (sourceImage != EGL_NO_IMAGE_KHR) destroyImage(display, sourceImage);
    if (pbuffer != EGL_NO_SURFACE) eglDestroySurface(display, pbuffer);
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    targets.clear();
    lastCopy = EGL_NO_SYNC_KHR;
    sourceImage = EGL_NO_IMAGE_KHR;
    sourceTexture = 0;
    program = 0;
    pbuffer = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    display = EGL_NO_DISPLAY;
}

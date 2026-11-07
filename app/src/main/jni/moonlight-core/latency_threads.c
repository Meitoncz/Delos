#define _GNU_SOURCE

#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <sched.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <time.h>
#include <unistd.h>

#include <android/log.h>

#include "latency_threads.h"

#define LOG_TAG "DelosThreads"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
// The tag of the stream diagnostics (DelosPyroDiag in MediaCodecDecoderRenderer)
#define DIAG_TAG "DelosPyroDiag"

// Process.THREAD_PRIORITY_URGENT_DISPLAY, the priority the render thread already runs at
#define STREAM_THREAD_NICE (-8)

static jclass LatencyClass;
static jmethodID OnNativeThreadMethod;
static jmethodID ReportWorkMethod;

static __thread bool VideoThreadTuned;
static bool NiceFailureLogged;

void LatencyThreadsInit(JNIEnv* env) {
    jclass local = (*env)->FindClass(env, "com/limelight/binding/video/LatencyThreads");
    if (local == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    jmethodID onNativeThread = (*env)->GetStaticMethodID(env, local, "onNativeThread", "(I)V");
    if (onNativeThread == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, local);
        return;
    }
    jmethodID reportWork = (*env)->GetStaticMethodID(env, local, "reportWork", "(J)V");
    if (reportWork == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, local);
        return;
    }
    OnNativeThreadMethod = onNativeThread;
    ReportWorkMethod = reportWork;
    LatencyClass = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
}

static void raiseThread(int tid) {
    if (setpriority(PRIO_PROCESS, (id_t)tid, STREAM_THREAD_NICE) != 0 &&
            !__atomic_exchange_n(&NiceFailureLogged, true, __ATOMIC_RELAXED)) {
        LOGI("setpriority(%d) failed: %s", STREAM_THREAD_NICE, strerror(errno));
    }
}

static void registerThread(JNIEnv* env, int tid) {
    if (LatencyClass == NULL) return;
    (*env)->CallStaticVoidMethod(env, LatencyClass, OnNativeThreadMethod, (jint)tid);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static uint64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

// moonlight-common-c sizes the video socket's receive buffer and reads the grant back, but only warns when
// the grant is below the request and does not expose the socket. Its descriptor is found by listing this
// process's UDP sockets: the video socket has by far the biggest receive buffer (the audio and control
// sockets keep the default). The kernel reports twice the value an app sets (bookkeeping overhead).
static void logUdpReceiveBuffers(void) {
    DIR* dir = opendir("/proc/self/fd");
    if (dir == NULL) return;

    char sockets[256] = "";
    int largestKb = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd <= 2 || fd == dirfd(dir)) continue;

        int type = 0, domain = 0, rcvbuf = 0;
        socklen_t len = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0 || type != SOCK_DGRAM) continue;
        len = sizeof(domain);
        if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &len) != 0 || (domain != AF_INET && domain != AF_INET6)) continue;
        len = sizeof(rcvbuf);
        if (getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, &len) != 0) continue;

        struct sockaddr_storage address;
        socklen_t addressLen = sizeof(address);
        int port = 0;
        if (getsockname(fd, (struct sockaddr*)&address, &addressLen) == 0) {
            port = ntohs(domain == AF_INET6 ? ((struct sockaddr_in6*)&address)->sin6_port
                                            : ((struct sockaddr_in*)&address)->sin_port);
        }
        int kb = rcvbuf / 1024;
        if (kb > largestKb) largestKb = kb;
        size_t used = strlen(sockets);
        snprintf(sockets + used, sizeof(sockets) - used, "%sport %d %d KB", used > 0 ? ", " : "", port, kb);
    }
    closedir(dir);
    __android_log_print(ANDROID_LOG_INFO, DIAG_TAG,
            "UDP receive buffers (SO_RCVBUF as the kernel reports it, double the granted size): %s | largest %d KB = the video "
            "socket; common-c requests about 2900 KB (11500 KB for PyroWave), so about 5800 KB (23000 KB) here is a full grant and less is clamped",
            sockets[0] != '\0' ? sockets : "no UDP sockets found", largestKb);
}

uint64_t LatencyFrameBegin(JNIEnv* env) {
    if (!VideoThreadTuned) {
        VideoThreadTuned = true;

        int tid = (int)gettid();
        raiseThread(tid);
        registerThread(env, tid);
        logUdpReceiveBuffers();
    }
    return nowNs();
}

void LatencyFrameEnd(JNIEnv* env, uint64_t beginNs) {
    if (LatencyClass == NULL) return;
    (*env)->CallStaticVoidMethod(env, LatencyClass, ReportWorkMethod, (jlong)(nowNs() - beginNs));
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

void LatencyTuneConnectionThreads(JNIEnv* env) {
    DIR* dir = opendir("/proc/self/task");
    if (dir == NULL) return;

    char raised[96] = "";
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        int tid = atoi(entry->d_name);
        if (tid <= 0) continue;

        char path[64];
        snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
        FILE* file = fopen(path, "r");
        if (file == NULL) continue;
        char name[32] = "";
        if (fgets(name, sizeof(name), file) == NULL) name[0] = '\0';
        fclose(file);
        name[strcspn(name, "\n")] = '\0';

        bool video = strcmp(name, "VideoRecv") == 0;
        if (!video && strcmp(name, "InputSend") != 0 && strcmp(name, "ControlRecv") != 0) continue;

        raiseThread(tid);
        // VideoRecv does not call into Delos when the decoder is not direct-submit (VideoDec does)
        if (video) registerThread(env, tid);
        size_t used = strlen(raised);
        snprintf(raised + used, sizeof(raised) - used, "%s%s", used > 0 ? " " : "", name);
    }
    closedir(dir);
    LOGI("raised to nice %d: %s", STREAM_THREAD_NICE, raised[0] != '\0' ? raised : "none found");
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_LatencyThreads_nativeSetAffinity(JNIEnv* env, jclass clazz, jint tid, jlong mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int cpu = 0; cpu < 64; cpu++) {
        if ((((uint64_t)mask) >> cpu) & 1) CPU_SET(cpu, &set);
    }
    return sched_setaffinity((pid_t)tid, sizeof(set), &set) == 0 ? 0 : errno;
}

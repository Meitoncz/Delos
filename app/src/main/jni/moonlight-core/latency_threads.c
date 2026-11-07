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
#include <time.h>
#include <unistd.h>

#include <android/log.h>

#include "latency_threads.h"

#define LOG_TAG "DelosLatency"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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

uint64_t LatencyFrameBegin(JNIEnv* env) {
    if (!VideoThreadTuned) {
        VideoThreadTuned = true;

        int tid = (int)gettid();
        raiseThread(tid);
        registerThread(env, tid);
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

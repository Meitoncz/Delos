#pragma once

#include <android/log.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

// Delos: copies one native log line into the app's own rolling diagnostic log (DiagLog.java, docs/DIAGNOSTICS.md) so a
// problem report carries the native side too. [priority] is an ANDROID_LOG_* value. Callable from any thread except a
// signal handler; it never blocks (the Java side drops a line when its queue is full) and ignores calls made before
// MoonBridge.init or after the VM is gone. Lines are limited to a flood-safe rate per second.
void DelosDiagLog(int priority, const char* tag, const char* message);

#ifdef __cplusplus
}
#endif

// Logs to logcat as before and to the diagnostic log. Only for events and failures, never per frame.
#define DELOS_LOG(priority, tag, ...) do { \
        char delosLogBuffer[1024]; \
        snprintf(delosLogBuffer, sizeof(delosLogBuffer), __VA_ARGS__); \
        __android_log_write((priority), (tag), delosLogBuffer); \
        DelosDiagLog((priority), (tag), delosLogBuffer); \
    } while (0)

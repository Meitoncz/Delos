#pragma once

#include <jni.h>
#include <stdint.h>

// Delos: scheduling of the latency-critical stream threads (see LatencyThreads.java). Everything here runs
// from Delos' own callbacks; nothing in moonlight-common-c is touched.

// Looks up the Java side once (MoonBridge.init); a missing class only disables these hooks.
void LatencyThreadsInit(JNIEnv* env);

// Raises the priority of the threads moonlight-common-c creates and never calls back from (InputSend,
// ControlRecv, VideoRecv), found by the names it gives them. Call once the connection has started.
void LatencyTuneConnectionThreads(JNIEnv* env);

// Brackets one video decode unit on the thread that receives it. The first call on a thread raises its
// priority and registers it with LatencyThreads; later calls cost a thread-local flag check and a clock read.
// The span between the two calls is the work duration of the frame, reported to the performance hint session.
uint64_t LatencyFrameBegin(JNIEnv* env);
void LatencyFrameEnd(JNIEnv* env, uint64_t beginNs);

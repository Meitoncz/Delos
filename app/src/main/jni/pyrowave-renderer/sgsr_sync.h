// Delos: sync-file helpers shared by the SGSR native outputs. A native fence (EGL_ANDROID_native_fence_sync) is a
// sync file descriptor: poll() with a zero timeout says whether the GPU work has finished, and the kernel records the
// signal time of each fence in CLOCK_MONOTONIC.
#pragma once
#include <algorithm>
#include <cstdint>
#include <ctime>
#include <poll.h>
#include <sys/ioctl.h>
#include <linux/sync_file.h>

namespace sgsr_sync {
// The signal time of a sync file's last fence in CLOCK_MONOTONIC ns, 0 when the kernel does not say.
inline int64_t fenceSignalNs(int fd) {
    struct sync_file_info info={};
    if(ioctl(fd,SYNC_IOC_FILE_INFO,&info)<0||info.num_fences==0) return 0;
    struct sync_fence_info fences[8];
    info.num_fences=std::min<uint32_t>(info.num_fences,8);
    info.sync_fence_info=reinterpret_cast<uint64_t>(fences);
    if(ioctl(fd,SYNC_IOC_FILE_INFO,&info)<0) return 0;
    uint64_t latest=0;
    for(uint32_t i=0;i<info.num_fences;i++) latest=std::max<uint64_t>(latest,fences[i].timestamp_ns);
    return int64_t(latest);
}
inline int64_t monotonicNs() {
    timespec now={};clock_gettime(CLOCK_MONOTONIC,&now);
    return int64_t(now.tv_sec)*1000000000LL+now.tv_nsec;
}
inline bool signalled(int fd) { pollfd poller={fd,POLLIN,0}; return poll(&poller,1,0)>0; }
}

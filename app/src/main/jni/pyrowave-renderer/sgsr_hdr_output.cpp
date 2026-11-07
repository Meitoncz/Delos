// Delos: SGSR's ten-bit P010 output. Reuses the proven PyroWave SurfaceControl/fence ownership model.
// The caller owns a current GLES context. No Vulkan state or game process is involved.
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/surface_control.h>
#include <android/data_space.h>
#include <android/hdr_metadata.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <poll.h>
#include <memory>
#include <mutex>
#include <type_traits>
#include <algorithm>
#include <cstring>
#include <ctime>
#include <sys/ioctl.h>
#include <linux/sync_file.h>

namespace {
struct Api {
    ASurfaceControl *(*create)(ANativeWindow *,const char *)=nullptr;
    void (*release)(ASurfaceControl *)=nullptr;
    ASurfaceTransaction *(*transaction)()=nullptr;
    void (*destroy)(ASurfaceTransaction *)=nullptr;
    void (*apply)(ASurfaceTransaction *)=nullptr;
    void (*buffer)(ASurfaceTransaction *,ASurfaceControl *,AHardwareBuffer *,int)=nullptr;
    void (*visible)(ASurfaceTransaction *,ASurfaceControl *,int8_t)=nullptr;
    void (*z)(ASurfaceTransaction *,ASurfaceControl *,int32_t)=nullptr;
    void (*geometry)(ASurfaceTransaction *,ASurfaceControl *,const ARect &,const ARect &,int32_t)=nullptr;
    void (*dataspace)(ASurfaceTransaction *,ASurfaceControl *,int32_t)=nullptr;
    void (*complete)(ASurfaceTransaction *,void *,void (*)(void *,ASurfaceTransactionStats *))=nullptr;
    int (*releaseFence)(ASurfaceTransactionStats *,ASurfaceControl *)=nullptr;
    void (*smpte)(ASurfaceTransaction *,ASurfaceControl *,AHdrMetadata_smpte2086 *)=nullptr;
    void (*cta)(ASurfaceTransaction *,ASurfaceControl *,AHdrMetadata_cta861_3 *)=nullptr;
    int (*allocate)(const AHardwareBuffer_Desc *,AHardwareBuffer **)=nullptr;
    void (*freeBuffer)(AHardwareBuffer *)=nullptr;
    int64_t (*latchTime)(ASurfaceTransactionStats *)=nullptr;
    int (*presentFence)(ASurfaceTransactionStats *)=nullptr;
    bool load() {
        // libandroid stays loaded for outstanding transaction callbacks.
        static void *lib=dlopen("libandroid.so",RTLD_NOW);
        if(!lib) return false;
        bool ok=true;
        auto get=[&](auto &f,const char *n){f=reinterpret_cast<std::remove_reference_t<decltype(f)>>(dlsym(lib,n));ok=ok&&f;};
        get(create,"ASurfaceControl_createFromWindow"); get(release,"ASurfaceControl_release");
        get(transaction,"ASurfaceTransaction_create"); get(destroy,"ASurfaceTransaction_delete"); get(apply,"ASurfaceTransaction_apply");
        get(buffer,"ASurfaceTransaction_setBuffer"); get(visible,"ASurfaceTransaction_setVisibility"); get(z,"ASurfaceTransaction_setZOrder");
        get(geometry,"ASurfaceTransaction_setGeometry"); get(dataspace,"ASurfaceTransaction_setBufferDataSpace");
        get(complete,"ASurfaceTransaction_setOnComplete"); get(releaseFence,"ASurfaceTransactionStats_getPreviousReleaseFenceFd");
        get(smpte,"ASurfaceTransaction_setHdrMetadata_smpte2086"); get(cta,"ASurfaceTransaction_setHdrMetadata_cta861_3");
        get(allocate,"AHardwareBuffer_allocate"); get(freeBuffer,"AHardwareBuffer_release");
        // Frame timing is optional: without it the stage timestamps are simply not available
        latchTime=reinterpret_cast<decltype(latchTime)>(dlsym(lib,"ASurfaceTransactionStats_getLatchTime"));
        presentFence=reinterpret_cast<decltype(presentFence)>(dlsym(lib,"ASurfaceTransactionStats_getPresentFenceFd"));
        return ok;
    }
};
constexpr int64_t kPending=-2,kUnavailable=-1;
// The signal time of a sync file's last fence in CLOCK_MONOTONIC ns, 0 when the kernel does not say.
int64_t fenceSignalNs(int fd) {
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
int64_t monotonicNs() {
    timespec now={};clock_gettime(CLOCK_MONOTONIC,&now);
    return int64_t(now.tv_sec)*1000000000LL+now.tv_nsec;
}
bool signalled(int fd) { pollfd poller={fd,POLLIN,0}; return poll(&poller,1,0)>0; }
// The times of one presented frame, all CLOCK_MONOTONIC ns: when the CPU flushed it, when its GPU work finished (the
// signal time of the native fence), when SurfaceFlinger latched it and when the display presented it (the present
// fence). kPending until known, kUnavailable when the platform does not give it. Guarded by Pool::lock.
struct FrameTiming {
    uint32_t seq=0;
    bool used=false,tailTaken=false;
    int64_t submitNs=0,gpuDoneNs=kPending,latchNs=kPending,presentNs=kPending;
    int gpuFd=-1,presentFd=-1;
    void closeFds(){ if(gpuFd>=0)close(gpuFd); if(presentFd>=0)close(presentFd); gpuFd=presentFd=-1; }
    // Resolves the fences that have signalled by now
    void harvest() {
        if(gpuFd>=0&&signalled(gpuFd)){ const int64_t t=fenceSignalNs(gpuFd); gpuDoneNs=t>0?t:kUnavailable; close(gpuFd); gpuFd=-1; }
        if(presentFd>=0&&signalled(presentFd)){ const int64_t t=fenceSignalNs(presentFd); presentNs=t>0?t:kUnavailable; close(presentFd); presentFd=-1; }
    }
};
struct Pool {
    static constexpr int kFrames=16;
    Api api;
    ASurfaceControl *control=nullptr;
    std::mutex lock;
    bool held[4]={};
    int fences[4]={-1,-1,-1,-1};
    AHardwareBuffer *buffers[4]={};
    FrameTiming frames[kFrames];
    uint32_t nextSeq=1;
    ~Pool(){
        for(int i=0;i<4;i++){if(fences[i]>=0)close(fences[i]);if(buffers[i])api.freeBuffer(buffers[i]);}
        for(auto &frame:frames)frame.closeFds();
        if(control)api.release(control);
    }
};
struct Completion { std::shared_ptr<Pool> pool; int previous; uint32_t seq; };
void completed(void *data,ASurfaceTransactionStats *stats) {
    std::unique_ptr<Completion> callback(static_cast<Completion *>(data));
    auto &p=*callback->pool;
    const int64_t latch=p.api.latchTime?p.api.latchTime(stats):-1;
    const int present=p.api.presentFence?p.api.presentFence(stats):-1;
    const int fence=callback->previous>=0?p.api.releaseFence(stats,p.control):-1;
    std::lock_guard<std::mutex> guard(p.lock);
    FrameTiming &frame=p.frames[callback->seq%Pool::kFrames];
    if(frame.used&&frame.seq==callback->seq){
        frame.latchNs=latch>0?latch:kUnavailable;
        if(present>=0) frame.presentFd=present; else frame.presentNs=kUnavailable;
    }else if(present>=0) close(present);
    if(callback->previous<0) return;
    int i=callback->previous;
    if(p.fences[i]>=0) close(p.fences[i]);
    p.fences[i]=fence; p.held[i]=false;
}
struct Output {
    std::shared_ptr<Pool> pool=std::make_shared<Pool>();
    ANativeWindow *window=nullptr;
    EGLDisplay display=eglGetCurrentDisplay();
    EGLImageKHR images[4]={};
    GLuint textures[4]={}, framebuffers[4]={};
    int width=0,height=0,parentWidth=0,parentHeight=0,current=-1,previous=-1;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage=nullptr;
    // Native fence hand-over (EGL_ANDROID_native_fence_sync); without it the frame is finished with glFinish
    PFNEGLCREATESYNCKHRPROC createSync=nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroySync=nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupFence=nullptr;
    bool nativeFence=false;
    ~Output() {
        if(pool->control){
            auto *t=pool->api.transaction();
            pool->api.visible(t,pool->control,ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            pool->api.buffer(t,pool->control,nullptr,-1);
            pool->api.complete(t,new Completion{pool,previous,0},completed);
            pool->api.apply(t);pool->api.destroy(t);
        }
        glDeleteFramebuffers(4,framebuffers); glDeleteTextures(4,textures);
        if(destroyImage)for(auto image:images)if(image)destroyImage(display,image);
        if(window)ANativeWindow_release(window);
    }
    bool create(JNIEnv *env,jobject surface,int targetWidth,int targetHeight) {
        auto &api=pool->api;
        if(!api.load()) return false;
        window=ANativeWindow_fromSurface(env,surface); if(!window)return false;
        // The SurfaceView's buffer space can be smaller than its physical view (720p -> 1080p).
        // SurfaceFlinger already scales that parent. Map the high-resolution child back into the
        // parent's coordinates, otherwise that scale is applied a second time and crops the video.
        parentWidth=ANativeWindow_getWidth(window);parentHeight=ANativeWindow_getHeight(window);
        width=targetWidth>0?targetWidth:parentWidth;
        height=targetHeight>0?targetHeight:parentHeight;
        if(width<1||height<1) return false;
        pool->control=api.create(window,"Delos SGSR HDR");if(!pool->control)return false;
        auto nativeBuffer=reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        auto createImage=reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage=reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        auto imageTexture=reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        if(!nativeBuffer||!createImage||!destroyImage||!imageTexture)return false;
        const char *eglExtensions=eglQueryString(display,EGL_EXTENSIONS);
        createSync=reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
        destroySync=reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
        dupFence=reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
        nativeFence=eglExtensions&&strstr(eglExtensions,"EGL_ANDROID_native_fence_sync")&&strstr(eglExtensions,"EGL_KHR_fence_sync")
                &&createSync&&destroySync&&dupFence;
        AHardwareBuffer_Desc desc={};desc.width=width;desc.height=height;desc.layers=1;
        desc.format=AHARDWAREBUFFER_FORMAT_YCbCr_P010;
        desc.usage=AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        glGenTextures(4,textures);glGenFramebuffers(4,framebuffers);
        for(int i=0;i<4;i++){
            if(api.allocate(&desc,&pool->buffers[i]))return false;
            const EGLint attrs[]={EGL_IMAGE_PRESERVED_KHR,EGL_TRUE,EGL_NONE};
            images[i]=createImage(display,EGL_NO_CONTEXT,EGL_NATIVE_BUFFER_ANDROID,nativeBuffer(pool->buffers[i]),attrs);
            if(!images[i])return false;
            glBindTexture(GL_TEXTURE_EXTERNAL_OES,textures[i]); imageTexture(GL_TEXTURE_EXTERNAL_OES,images[i]);
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_EXTERNAL_OES,textures[i],0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)return false;
        }
        glBindFramebuffer(GL_FRAMEBUFFER,0);
        __android_log_print(ANDROID_LOG_INFO,"SGSR","P010 output ready: %dx%d, parent: %dx%d, flip-V",width,height,parentWidth,parentHeight);
        return glGetError()==GL_NO_ERROR;
    }
    int acquire() {
        std::lock_guard<std::mutex> guard(pool->lock);
        for(int i=0;i<4;i++){
            if(pool->held[i])continue;
            if(pool->fences[i]>=0){
                pollfd poller={pool->fences[i],POLLIN,0};
                if(poll(&poller,1,0)<=0)continue;
                close(pool->fences[i]);pool->fences[i]=-1;
            }
            current=i;pool->held[i]=true;glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[i]);return i;
        }
        return -1; // Drop instead of reusing a buffer SurfaceFlinger might still be reading.
    }
    // Flushes the frame and returns a native fence for it, or -1 when the driver cannot make one and the GPU
    // had to be waited for instead (glFinish), which is the safe way to hand the buffer over without a fence.
    int flushWithFence() {
        if(nativeFence){
            const EGLint attributes[]={EGL_SYNC_NATIVE_FENCE_FD_ANDROID,EGL_NO_NATIVE_FENCE_FD_ANDROID,EGL_NONE};
            EGLSyncKHR sync=createSync(display,EGL_SYNC_NATIVE_FENCE_ANDROID,attributes);
            // The native fence exists once the commands are flushed
            glFlush();
            if(sync!=EGL_NO_SYNC_KHR){
                const int fd=dupFence(display,sync);
                destroySync(display,sync);
                if(fd>=0) return fd;
            }
        }
        glFinish();
        return -1;
    }
    // Returns the sequence number of the presented frame, 0 when there was nothing to present.
    uint32_t present(JNIEnv *env,jboolean hdr,jbyteArray metadata) {
        if(current<0)return 0;
        // The buffer goes to SurfaceFlinger with the fence of this frame's GPU work, so it reads the picture only
        // once it is complete and the GL thread does not wait for the GPU. Release fences separately protect the
        // reuse of a buffer; no CPU pixel copy or eight-bit intermediate is used.
        const int64_t submitNs=monotonicNs();
        const int fence=flushWithFence();
        uint32_t seq;
        {
            std::lock_guard<std::mutex> guard(pool->lock);
            seq=pool->nextSeq++;
            FrameTiming &frame=pool->frames[seq%Pool::kFrames];
            frame.closeFds();
            frame=FrameTiming();
            frame.used=true;frame.seq=seq;frame.submitNs=submitNs;
            // Without a fence the GPU was waited for (glFinish): it is done now
            if(fence<0) frame.gpuDoneNs=monotonicNs();
            else{
                frame.gpuFd=dup(fence);
                if(frame.gpuFd<0) frame.gpuDoneNs=kUnavailable;
            }
        }
        auto &api=pool->api;auto *t=api.transaction();
        // The transaction owns the fence descriptor from here on
        api.buffer(t,pool->control,pool->buffers[current],fence);
        api.dataspace(t,pool->control,hdr?ADATASPACE_BT2020_ITU_PQ:ADATASPACE_BT709);
        const ARect source={0,0,width,height};
        const ARect dest={0,0,parentWidth,parentHeight};
        // EGL window presentation supplies the GL-to-Android vertical flip for SDR. This raw
        // P010 framebuffer bypasses EGL presentation, so apply it explicitly on the child layer.
        api.geometry(t,pool->control,source,dest,ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL);api.z(t,pool->control,1);api.visible(t,pool->control,ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        AHdrMetadata_smpte2086 mastering={};AHdrMetadata_cta861_3 light={};
        if(hdr&&metadata&&env->GetArrayLength(metadata)>=24){
            unsigned char data[24];env->GetByteArrayRegion(metadata,0,24,reinterpret_cast<jbyte *>(data));
            auto value=[&](int offset){return unsigned(data[offset])|(unsigned(data[offset+1])<<8);};
            mastering.displayPrimaryRed={value(0)/50000.f,value(2)/50000.f};
            mastering.displayPrimaryGreen={value(4)/50000.f,value(6)/50000.f};
            mastering.displayPrimaryBlue={value(8)/50000.f,value(10)/50000.f};
            mastering.whitePoint={value(12)/50000.f,value(14)/50000.f};
            mastering.maxLuminance=value(16);mastering.minLuminance=value(18)/10000.f;
            light.maxContentLightLevel=value(20);light.maxFrameAverageLightLevel=value(22);
            api.smpte(t,pool->control,&mastering);api.cta(t,pool->control,&light);
        }else{api.smpte(t,pool->control,nullptr);api.cta(t,pool->control,nullptr);}
        api.complete(t,new Completion{pool,previous,seq},completed);api.apply(t);api.destroy(t);
        previous=current;current=-1;
        return seq;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeCreateHdr(JNIEnv *env,jclass,jobject surface,jint width,jint height){
    auto output=std::make_unique<Output>();if(!output->create(env,surface,width,height))return 0;return reinterpret_cast<jlong>(output.release());
}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeAcquireHdr(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->acquire();}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrWidth(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->width;}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrHeight(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->height;}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativePresentHdr(JNIEnv *env,jclass,jlong handle,jboolean hdr,jbyteArray metadata){return jint(reinterpret_cast<Output *>(handle)->present(env,hdr,metadata));}
extern "C" JNIEXPORT void JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeDestroyHdr(JNIEnv *,jclass,jlong handle){delete reinterpret_cast<Output *>(handle);}
// Copies the GPU tails (fence signal time minus the flush time, ns) of frames that finished since the last call
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrGpuTails(JNIEnv *env,jclass,jlong handle,jlongArray out){
    auto &pool=*reinterpret_cast<Output *>(handle)->pool;
    const jsize capacity=std::min<jsize>(env->GetArrayLength(out),jsize(Pool::kFrames));
    jlong tails[Pool::kFrames];
    int count=0;
    {
        std::lock_guard<std::mutex> guard(pool.lock);
        // Oldest first, so the smoothing on the Java side sees them in presentation order
        for(uint32_t seq=pool.nextSeq>Pool::kFrames?pool.nextSeq-Pool::kFrames:1;seq<pool.nextSeq&&count<capacity;seq++){
            FrameTiming &frame=pool.frames[seq%Pool::kFrames];
            if(!frame.used||frame.seq!=seq) continue;
            frame.harvest();
            if(frame.tailTaken||frame.gpuDoneNs<frame.submitNs) continue;
            frame.tailTaken=true;
            tails[count++]=frame.gpuDoneNs-frame.submitNs;
        }
    }
    if(count>0)env->SetLongArrayRegion(out,0,count,tails);
    return count;
}
// The CLOCK_MONOTONIC times (ns) of a presented frame: out[0] GPU done, out[1] SurfaceFlinger latch, out[2] display
// present, -1 for one the platform does not give. Returns 0 when all are known, 1 while one is still pending and -1
// when the frame is no longer remembered.
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrFrameTimestamps(JNIEnv *env,jclass,jlong handle,jint seq,jlongArray out){
    auto &pool=*reinterpret_cast<Output *>(handle)->pool;
    jlong values[3];
    {
        std::lock_guard<std::mutex> guard(pool.lock);
        FrameTiming &frame=pool.frames[uint32_t(seq)%Pool::kFrames];
        if(!frame.used||frame.seq!=uint32_t(seq)) return -1;
        frame.harvest();
        if(frame.gpuDoneNs==kPending||frame.latchNs==kPending||frame.presentNs==kPending) return 1;
        values[0]=frame.gpuDoneNs;values[1]=frame.latchNs;values[2]=frame.presentNs;
    }
    env->SetLongArrayRegion(out,0,3,values);
    return 0;
}

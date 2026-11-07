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
        return ok;
    }
};
struct Pool {
    Api api;
    ASurfaceControl *control=nullptr;
    std::mutex lock;
    bool held[4]={};
    int fences[4]={-1,-1,-1,-1};
    AHardwareBuffer *buffers[4]={};
    ~Pool(){ for(int i=0;i<4;i++){if(fences[i]>=0)close(fences[i]);if(buffers[i])api.freeBuffer(buffers[i]);}if(control)api.release(control); }
};
struct Completion { std::shared_ptr<Pool> pool; int previous; };
void completed(void *data,ASurfaceTransactionStats *stats) {
    std::unique_ptr<Completion> callback(static_cast<Completion *>(data));
    if(callback->previous<0) return;
    auto &p=*callback->pool;
    int fence=p.api.releaseFence(stats,p.control);
    std::lock_guard<std::mutex> guard(p.lock);
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
    // Fences of frames in flight (GL thread only), and the GPU tails harvested since Java last asked
    struct Timing{int fd=-1;int64_t submitNs=0;};
    static constexpr int kTimings=8,kTails=16;
    Timing timings[kTimings];
    int timingHead=0,timingCount=0;
    int64_t tails[kTails]={};
    int tailCount=0;
    ~Output() {
        for(auto &timing:timings)if(timing.fd>=0)close(timing.fd);
        if(pool->control){
            auto *t=pool->api.transaction();
            pool->api.visible(t,pool->control,ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            pool->api.buffer(t,pool->control,nullptr,-1);
            pool->api.complete(t,new Completion{pool,previous},completed);
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
    // The signal time of a sync file's last fence in CLOCK_MONOTONIC ns, 0 when the kernel does not say.
    static int64_t fenceSignalNs(int fd) {
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
    static int64_t monotonicNs() {
        timespec now={};clock_gettime(CLOCK_MONOTONIC,&now);
        return int64_t(now.tv_sec)*1000000000LL+now.tv_nsec;
    }
    // Collects the GPU-completion times of earlier frames whose fences have signalled (in submission order). The
    // time from the flush to the signal is how long the GPU kept working after the CPU had submitted the frame.
    void harvest() {
        while(timingCount>0){
            Timing &timing=timings[timingHead];
            pollfd poller={timing.fd,POLLIN,0};
            if(poll(&poller,1,0)<=0) break;
            const int64_t done=fenceSignalNs(timing.fd);
            if(done>=timing.submitNs&&tailCount<kTails) tails[tailCount++]=done-timing.submitNs;
            close(timing.fd);timing.fd=-1;
            timingHead=(timingHead+1)%kTimings;timingCount--;
        }
    }
    // Keeps a duplicate of the frame's fence for harvest(); when the ring is full the oldest is dropped.
    void track(int fd,int64_t submitNs) {
        if(timingCount==kTimings){close(timings[timingHead].fd);timingHead=(timingHead+1)%kTimings;timingCount--;}
        Timing &slot=timings[(timingHead+timingCount)%kTimings];
        slot.fd=fd;slot.submitNs=submitNs;timingCount++;
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
    void present(JNIEnv *env,jboolean hdr,jbyteArray metadata) {
        if(current<0)return;
        harvest();
        // The buffer goes to SurfaceFlinger with the fence of this frame's GPU work, so it reads the picture only
        // once it is complete and the GL thread does not wait for the GPU. Release fences separately protect the
        // reuse of a buffer; no CPU pixel copy or eight-bit intermediate is used.
        const int64_t submitNs=monotonicNs();
        const int fence=flushWithFence();
        if(fence>=0){
            const int own=dup(fence);
            if(own>=0) track(own,submitNs);
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
        api.complete(t,new Completion{pool,previous},completed);api.apply(t);api.destroy(t);
        previous=current;current=-1;
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeCreateHdr(JNIEnv *env,jclass,jobject surface,jint width,jint height){
    auto output=std::make_unique<Output>();if(!output->create(env,surface,width,height))return 0;return reinterpret_cast<jlong>(output.release());
}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeAcquireHdr(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->acquire();}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrWidth(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->width;}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrHeight(JNIEnv *,jclass,jlong handle){return reinterpret_cast<Output *>(handle)->height;}
extern "C" JNIEXPORT void JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativePresentHdr(JNIEnv *env,jclass,jlong handle,jboolean hdr,jbyteArray metadata){reinterpret_cast<Output *>(handle)->present(env,hdr,metadata);}
extern "C" JNIEXPORT void JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeDestroyHdr(JNIEnv *,jclass,jlong handle){delete reinterpret_cast<Output *>(handle);}
extern "C" JNIEXPORT jint JNICALL Java_com_limelight_binding_video_SgsrRenderer_nativeHdrGpuTails(JNIEnv *env,jclass,jlong handle,jlongArray out){
    auto *output=reinterpret_cast<Output *>(handle);
    const jsize capacity=env->GetArrayLength(out);
    const int count=std::min<int>(output->tailCount,capacity);
    if(count>0)env->SetLongArrayRegion(out,0,count,reinterpret_cast<const jlong *>(output->tails));
    output->tailCount=0;
    return count;
}

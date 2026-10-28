// PyroWave video renderer for Delos.
//
// Adapted from the PyroWave renderer of joemossjr16/artemis-android-pyrowave (GPL-3.0, like Delos). Delos
// changes: frames come straight from moonlight-common-c (PyroWave_SubmitDecodeUnit, never through Java)
// and are parsed by the official client's framing (pyrowaveframing.cpp, from Nonary/moonlight-qt), which
// handles record framing and frames that lost packets; the colour conversion follows the stream's
// range and matrix.
//
// PyroWave is an intra-only wavelet codec decoded with Vulkan compute, so it cannot
// go through MediaCodec. This renderer owns a Vulkan device and a swapchain on the
// stream's Surface, lends the device to PyroWave, decodes each frame into three
// Y/Cb/Cr plane images and converts them to RGB in a fragment shader.
//
// Vulkan is loaded at runtime (libvulkan.so is not available on every Android
// version this app supports), so this library links neither libvulkan nor any
// Vulkan prototypes.

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <pyrowave/pyrowave.h>

#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/rect.h>
#include <android/surface_control.h>
#include <poll.h>
#include <unistd.h>
#include <signal.h>
#include <sys/syscall.h>
#include <unwind.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <time.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <memory>
#include <mutex>
#include <vector>

#include "shaders_spv.h"
#include "pyrowaveframing.h"
#include "pyrowave_yuv_gl.h"

extern "C" {
#include "Limelight.h"
}

#define LOG_TAG "PyroWave"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    // Results of Renderer::submit
    constexpr int SUBMIT_OK = 0;
    constexpr int SUBMIT_SKIPPED = 1;
    constexpr int SUBMIT_ERROR = -1;

    constexpr uint64_t ACQUIRE_TIMEOUT_NS = 250'000'000;
    constexpr uint64_t FENCE_TIMEOUT_NS = 2'000'000'000;

    // Colour conversion of the planes (push constants of planar_csc.frag)
    struct CscParams {
        int32_t fullRange;
        // 0 = BT.601, 1 = BT.709, 2 = BT.2020
        int32_t matrix;
        // R16 planes (10-bit streams), normalised like FFmpeg's 16-bit formats
        int32_t sixteenBit;
    };

    struct PresentParams {
        float sharpness;
        int32_t hdr;
    };

    // Delos: the colour space extensions are optional; without them a stream stays SDR
    const char *const INSTANCE_EXTENSIONS[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    const char *const DEVICE_EXTENSIONS[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
    };

#define VK_GLOBAL_FUNCTIONS(X) \
    X(CreateInstance) \
    X(EnumerateInstanceVersion) \
    X(EnumerateInstanceExtensionProperties)

#define VK_INSTANCE_FUNCTIONS(X) \
    X(DestroyInstance) \
    X(EnumeratePhysicalDevices) \
    X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceFeatures2) \
    X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceMemoryProperties) \
    X(CreateDevice) \
    X(GetDeviceProcAddr) \
    X(CreateAndroidSurfaceKHR) \
    X(DestroySurfaceKHR) \
    X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(GetPhysicalDeviceSurfacePresentModesKHR) \
    X(EnumerateDeviceExtensionProperties)

#define VK_DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) \
    X(GetDeviceQueue) \
    X(DeviceWaitIdle) \
    X(CreateImage) \
    X(DestroyImage) \
    X(GetImageMemoryRequirements) \
    X(AllocateMemory) \
    X(FreeMemory) \
    X(BindImageMemory) \
    X(CreateImageView) \
    X(DestroyImageView) \
    X(CreateSampler) \
    X(DestroySampler) \
    X(CreateDescriptorSetLayout) \
    X(DestroyDescriptorSetLayout) \
    X(CreatePipelineLayout) \
    X(DestroyPipelineLayout) \
    X(CreateDescriptorPool) \
    X(DestroyDescriptorPool) \
    X(AllocateDescriptorSets) \
    X(UpdateDescriptorSets) \
    X(CreateShaderModule) \
    X(DestroyShaderModule) \
    X(CreateRenderPass) \
    X(DestroyRenderPass) \
    X(CreateGraphicsPipelines) \
    X(CreateComputePipelines) \
    X(DestroyPipeline) \
    X(CreateFramebuffer) \
    X(DestroyFramebuffer) \
    X(CreateCommandPool) \
    X(DestroyCommandPool) \
    X(AllocateCommandBuffers) \
    X(BeginCommandBuffer) \
    X(EndCommandBuffer) \
    X(ResetCommandBuffer) \
    X(CmdPipelineBarrier) \
    X(CmdBeginRenderPass) \
    X(CmdEndRenderPass) \
    X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) \
    X(CmdSetViewport) \
    X(CmdSetScissor) \
    X(CmdDraw) \
    X(CmdDispatch) \
    X(CmdPushConstants) \
    X(CreateFence) \
    X(DestroyFence) \
    X(WaitForFences) \
    X(ResetFences) \
    X(CreateSemaphore) \
    X(DestroySemaphore) \
    X(QueueSubmit) \
    X(CreateSwapchainKHR) \
    X(DestroySwapchainKHR) \
    X(GetSwapchainImagesKHR) \
    X(AcquireNextImageKHR) \
    X(QueuePresentKHR) \
    X(CreateQueryPool) \
    X(DestroyQueryPool) \
    X(CmdResetQueryPool) \
    X(CmdWriteTimestamp) \
    X(GetQueryPoolResults) \
    X(CreateBuffer) \
    X(DestroyBuffer) \
    X(GetBufferMemoryRequirements) \
    X(BindBufferMemory) \
    X(MapMemory) \
    X(CmdCopyImageToBuffer) \
    X(CmdCopyImage) \
    X(InvalidateMappedMemoryRanges)

    struct VulkanLoader {
        void *library = nullptr;
        PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
#define X(name) PFN_vk##name name = nullptr;
        VK_GLOBAL_FUNCTIONS(X)
        VK_INSTANCE_FUNCTIONS(X)
        VK_DEVICE_FUNCTIONS(X)
#undef X

        ~VulkanLoader() {
            if (library != nullptr) {
                dlclose(library);
            }
        }

        bool loadGlobal() {
            library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
            if (library == nullptr) {
                LOGI("No Vulkan loader on this device");
                return false;
            }
            GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
            if (GetInstanceProcAddr == nullptr) {
                return false;
            }
#define X(name) name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(VK_NULL_HANDLE, "vk" #name));
            VK_GLOBAL_FUNCTIONS(X)
#undef X
            // vkEnumerateInstanceVersion is missing on Vulkan 1.0 loaders, which is fine: 1.0 is not enough.
            return CreateInstance != nullptr && EnumerateInstanceVersion != nullptr;
        }

        bool loadInstance(VkInstance instance) {
            bool ok = true;
#define X(name) \
            name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(instance, "vk" #name)); \
            ok = ok && name != nullptr;
            VK_INSTANCE_FUNCTIONS(X)
#undef X
            return ok;
        }

        bool loadDevice(VkDevice device) {
            bool ok = true;
#define X(name) \
            name = reinterpret_cast<PFN_vk##name>(GetDeviceProcAddr(device, "vk" #name)); \
            ok = ok && name != nullptr;
            VK_DEVICE_FUNCTIONS(X)
#undef X
            return ok;
        }
    };

    // Features PyroWave's decode kernels use unconditionally. shaderFloat16 is optional.
    struct FeatureProbe {
        bool ok = false;
        bool float16 = false;
        bool hostQueryReset = false;
        uint32_t apiVersion = 0;
        char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = {};
    };

    FeatureProbe probeFeatures(const VulkanLoader &vk, VkPhysicalDevice device) {
        FeatureProbe probe;
        VkPhysicalDeviceProperties props;
        vk.GetPhysicalDeviceProperties(device, &props);
        probe.apiVersion = props.apiVersion;
        std::memcpy(probe.name, props.deviceName, sizeof(probe.name));
        if (props.apiVersion < VK_API_VERSION_1_3) {
            return probe;
        }

        VkPhysicalDeviceVulkan13Features f13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
        f12.pNext = &f13;
        VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        f2.pNext = &f12;
        vk.GetPhysicalDeviceFeatures2(device, &f2);

        probe.ok = f2.features.shaderInt16 && f12.storageBuffer8BitAccess && f12.timelineSemaphore &&
                   f13.subgroupSizeControl && f13.computeFullSubgroups && f13.synchronization2;
        probe.float16 = f12.shaderFloat16;
        probe.hostQueryReset = f12.hostQueryReset;
        return probe;
    }

    bool apiVersionSupported() {
        uint32_t major = 0, minor = 0, patch = 0;
        pyrowave_get_api_version(&major, &minor, &patch);
        if (major != PYROWAVE_API_VERSION_MAJOR || minor != PYROWAVE_API_VERSION_MINOR) {
            LOGE("PyroWave runtime %u.%u.%u does not match %u.%u", major, minor, patch,
                 PYROWAVE_API_VERSION_MAJOR, PYROWAVE_API_VERSION_MINOR);
            return false;
        }
        return true;
    }

    uint64_t nowUs() {
        timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return uint64_t(ts.tv_sec) * 1000000u + uint64_t(ts.tv_nsec) / 1000u;
    }

    // Delos: where the video thread is, so a stall can be pinned down from the stats thread
    // (PyroWaveRenderer.takeStats once a second): see nativeTakeStats
    enum VideoStage {
        STAGE_IDLE, STAGE_LOCK, STAGE_PARSE, STAGE_FRAME_FENCE, STAGE_HDR_SWITCH, STAGE_DECODE, STAGE_YUV_BUFFER,
        STAGE_RELEASE_FENCE, STAGE_GL_SOURCE, STAGE_PACK_FENCE, STAGE_COPY, STAGE_TRANSACTION, STAGE_ACQUIRE,
        STAGE_SWAPCHAIN_SUBMIT, STAGE_PRESENT, STAGE_COUNT
    };
    const char *const STAGE_NAMES[STAGE_COUNT] = {
        "idle (waiting for a frame)", "waiting for the renderer lock", "parsing the frame", "waiting for the previous frame",
        "switching HDR", "decoding", "taking a P010 buffer", "waiting for a P010 release fence",
        "waiting for the GL copy to free the source", "waiting for the decode and pack", "copying into P010",
        "applying the transaction", "acquiring a swapchain image", "submitting the draw", "presenting"
    };
    std::atomic<int> videoStage{STAGE_IDLE};
    std::atomic<uint64_t> videoStageSinceUs{0};
    std::atomic<uint64_t> lastFrameUs{0};

    // A stuck video thread logs its own stack (signal sent from nativeTakeStats); frames are library
    // offsets, symbolised offline against the unstripped libmoonlight-core.so
    std::atomic<pid_t> videoTid{0};
    constexpr int STACK_SIGNAL = 50;

    struct StackState {
        uintptr_t pcs[40];
        int count = 0;
    };

    _Unwind_Reason_Code collectFrame(_Unwind_Context *context, void *arg) {
        auto *state = static_cast<StackState *>(arg);
        const uintptr_t pc = _Unwind_GetIP(context);
        if (pc != 0) {
            state->pcs[state->count++] = pc;
        }
        return state->count < int(std::size(state->pcs)) ? _URC_NO_REASON : _URC_END_OF_STACK;
    }

    void logOwnStack(int) {
        StackState state;
        _Unwind_Backtrace(collectFrame, &state);
        for (int i = 0; i < state.count; ++i) {
            Dl_info info = {};
            if (dladdr(reinterpret_cast<void *>(state.pcs[i]), &info) && info.dli_fname != nullptr) {
                const char *name = std::strrchr(info.dli_fname, '/');
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "  #%02d %s+0x%zx %s", i, name ? name + 1 : info.dli_fname,
                                    size_t(state.pcs[i] - reinterpret_cast<uintptr_t>(info.dli_fbase)),
                                    info.dli_sname ? info.dli_sname : "");
            } else {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "  #%02d 0x%zx", i, size_t(state.pcs[i]));
            }
        }
    }

    void installStackSignal() {
        static std::once_flag once;
        std::call_once(once, [] {
            struct sigaction action = {};
            action.sa_handler = logOwnStack;
            sigemptyset(&action.sa_mask);
            sigaction(STACK_SIGNAL, &action, nullptr);
        });
    }

    std::string readTaskFile(pid_t tid, const char *file) {
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/task/%d/%s", tid, file);
        FILE *f = fopen(path, "r");
        if (f == nullptr) {
            return "?";
        }
        char text[256] = {};
        size_t n = fread(text, 1, sizeof(text) - 1, f);
        fclose(f);
        text[n] = 0;
        return text;
    }

    void enterStage(VideoStage stage) {
        videoStage.store(stage, std::memory_order_relaxed);
        videoStageSinceUs.store(nowUs(), std::memory_order_relaxed);
    }

    const char *presentModeName(VkPresentModeKHR mode) {
        switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR: return "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR: return "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR: return "FIFO";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR: return "FIFO_RELAXED";
            default: return "other";
        }
    }

    // Delos: SurfaceControl and AHardwareBuffer (API 29 / 26), looked up at run time because the native
    // code builds for API 21. They carry the P010 HDR output (see "YUV output" in Renderer).
    struct SurfaceApi {
        ASurfaceControl *(*createFromWindow)(ANativeWindow *, const char *) = nullptr;
        void (*releaseControl)(ASurfaceControl *) = nullptr;
        ASurfaceTransaction *(*createTransaction)() = nullptr;
        void (*deleteTransaction)(ASurfaceTransaction *) = nullptr;
        void (*apply)(ASurfaceTransaction *) = nullptr;
        void (*setBuffer)(ASurfaceTransaction *, ASurfaceControl *, AHardwareBuffer *, int) = nullptr;
        void (*setVisibility)(ASurfaceTransaction *, ASurfaceControl *, int8_t) = nullptr;
        void (*setZOrder)(ASurfaceTransaction *, ASurfaceControl *, int32_t) = nullptr;
        void (*setGeometry)(ASurfaceTransaction *, ASurfaceControl *, const ARect &, const ARect &, int32_t) = nullptr;
        void (*setTransparency)(ASurfaceTransaction *, ASurfaceControl *, int8_t) = nullptr;
        void (*setDataSpace)(ASurfaceTransaction *, ASurfaceControl *, int32_t) = nullptr;
        void (*setOnComplete)(ASurfaceTransaction *, void *, void (*)(void *, ASurfaceTransactionStats *)) = nullptr;
        int (*previousReleaseFence)(ASurfaceTransactionStats *, ASurfaceControl *) = nullptr;
        void (*reparent)(ASurfaceTransaction *, ASurfaceControl *, ASurfaceControl *) = nullptr;
        int (*allocate)(const AHardwareBuffer_Desc *, AHardwareBuffer **) = nullptr;
        void (*releaseBuffer)(AHardwareBuffer *) = nullptr;
        int (*lockPlanes)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *) = nullptr;
        int (*unlock)(AHardwareBuffer *, int32_t *) = nullptr;

        bool load() {
            void *android = dlopen("libandroid.so", RTLD_NOW);
            if (android == nullptr) {
                return false;
            }
            bool ok = true;
            const auto get = [&](auto &function, const char *name) {
                function = reinterpret_cast<std::remove_reference_t<decltype(function)>>(dlsym(android, name));
                ok = ok && function != nullptr;
            };
            get(createFromWindow, "ASurfaceControl_createFromWindow");
            get(releaseControl, "ASurfaceControl_release");
            get(createTransaction, "ASurfaceTransaction_create");
            get(deleteTransaction, "ASurfaceTransaction_delete");
            get(apply, "ASurfaceTransaction_apply");
            get(setBuffer, "ASurfaceTransaction_setBuffer");
            get(setVisibility, "ASurfaceTransaction_setVisibility");
            get(setZOrder, "ASurfaceTransaction_setZOrder");
            get(setGeometry, "ASurfaceTransaction_setGeometry");
            get(setTransparency, "ASurfaceTransaction_setBufferTransparency");
            get(setDataSpace, "ASurfaceTransaction_setBufferDataSpace");
            get(setOnComplete, "ASurfaceTransaction_setOnComplete");
            get(previousReleaseFence, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
            get(reparent, "ASurfaceTransaction_reparent");
            get(allocate, "AHardwareBuffer_allocate");
            get(releaseBuffer, "AHardwareBuffer_release");
            get(lockPlanes, "AHardwareBuffer_lockPlanes");
            get(unlock, "AHardwareBuffer_unlock");
            return ok;
        }
    };

    constexpr int YUV_BUFFERS = 4;

    // Which P010 buffers SurfaceFlinger still holds, updated from its completion callbacks (binder threads).
    // Shared with the callbacks so it outlives the renderer; the surface control is released with it.
    struct YuvReleases {
        std::mutex lock;
        bool held[YUV_BUFFERS] = {};
        int fence[YUV_BUFFERS] = {-1, -1, -1, -1};
        SurfaceApi *api = nullptr;
        ASurfaceControl *control = nullptr;

        ~YuvReleases() {
            for (int fd : fence) {
                if (fd >= 0) close(fd);
            }
            if (control != nullptr) {
                api->releaseControl(control);
            }
        }
    };

    struct YuvCallback {
        std::shared_ptr<YuvReleases> releases;
        // The buffer this transaction replaced, which SurfaceFlinger gives back
        int previous;
    };

    void onYuvTransactionComplete(void *context, ASurfaceTransactionStats *stats) {
        auto *callback = static_cast<YuvCallback *>(context);
        auto &releases = *callback->releases;
        if (callback->previous >= 0) {
            const int fd = releases.api->previousReleaseFence(stats, releases.control);
            std::lock_guard<std::mutex> lock(releases.lock);
            if (releases.fence[callback->previous] >= 0) {
                close(releases.fence[callback->previous]);
            }
            releases.fence[callback->previous] = fd;
            releases.held[callback->previous] = false;
        }
        delete callback;
    }

    struct Plane {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t width = 0;
        uint32_t height = 0;
    };

    class Renderer {
    public:
        ~Renderer() {
            destroy();
        }

        bool create(ANativeWindow *nativeWindow, int streamWidth, int streamHeight, int frameRate,
                    int targetWidth, int targetHeight, bool fullChroma, bool fullRange, int matrix,
                    bool tenBitStream, int upscalingMode, int supersamplingLevel, float sharpness) {
            window = nativeWindow;
            // API 28; Delos builds the native code for API 21
            void *android = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
            if (android == nullptr) {
                android = dlopen("libandroid.so", RTLD_NOW);
            }
            if (android != nullptr) {
                setBuffersDataSpace = reinterpret_cast<SetBuffersDataSpace>(
                    dlsym(android, "ANativeWindow_setBuffersDataSpace"));
            }
            LOGI("ANativeWindow_setBuffersDataSpace %s", setBuffersDataSpace != nullptr ? "found" : "missing");
            chroma444 = fullChroma;
            tenBit = tenBitStream;
            planeFormat = tenBit ? VK_FORMAT_R16_UNORM : VK_FORMAT_R8_UNORM;
            sdrMatrix = matrix;
            // The host may already be in HDR mode; later changes arrive through setHdrMode()
            hdrActive = tenBit && LiGetCurrentHostDisplayHdrMode();
            hdrWanted = hdrActive;
            csc = {fullRange ? 1 : 0, hdrActive ? 2 : matrix, tenBit ? 1 : 0};
            frameRateHz = frameRate > 0 ? frameRate : 60;
            sgsrMode.store(std::clamp(upscalingMode, 0, 4));
            sgsrAppliedMode = sgsrMode.load();
            sgsrSharpness.store(std::clamp(sharpness, 0.0f, 1.0f));
            sgsrAppliedSharpness = sgsrSharpness.load();
            sgsrApplied = sgsrAppliedMode != 0;
            sgsrLevel.store(supersamplingLevel >= 2 ? 2 : 1);
            sgsrAppliedLevel = sgsrLevel.load();
            width = uint32_t(streamWidth);
            height = uint32_t(streamHeight);
            sgsrTargetWidth = uint32_t(std::max(targetWidth, streamWidth));
            sgsrTargetHeight = uint32_t(std::max(targetHeight, streamHeight));
            const bool ok = apiVersionSupported() && createInstanceAndSurface() && createDevice() &&
                   createDecoder() && createPlanes() && createSwapchain() && createPipeline() &&
                   createFrameResources();
            if (ok && !createSgsrMotion()) {
                LOGW("PyroWave upscaler initialization failed; direct rendering remains active");
                destroySgsrMotion();
                sgsrAvailable = false;
                sgsrAppliedMode = 0;
                sgsrApplied = false;
            } else if (ok) {
                sgsrAvailable = true;
                reportAppliedMode.store(sgsrAppliedMode);
            }
            if (ok) {
                applyHdrMetadata();
            }
            return ok;
        }

        // Delos: the host switched HDR on or off (only 10-bit streams follow); applied before the next frame
        void setHdrMode(bool enabled) {
            hdrWanted = tenBit && enabled;
        }

        void setUpscalingMode(int mode) { sgsrMode.store(std::clamp(mode, 0, 4)); }
        int getUpscalingMode() const { return reportAppliedMode.load(); }
        bool isUpscalingEnabled() const { return getUpscalingMode() != 0; }
        void setSharpness(float sharpness) { sgsrSharpness.store(std::clamp(sharpness, 0.0f, 1.0f)); }
        void setSupersamplingLevel(int level) { sgsrLevel.store(level >= 2 ? 2 : 1); }
        bool isSupersampling() const {
            return getUpscalingMode() != 0 && reportInternalWidth.load() > reportPresentationWidth.load() &&
                   reportInternalHeight.load() > reportPresentationHeight.load();
        }
        std::string scaleDescription() const {
            if(!isUpscalingEnabled())return {};
            const uint32_t internalWidth=reportInternalWidth.load();
            const uint32_t internalHeight=reportInternalHeight.load();
            const uint32_t presentationWidth=reportPresentationWidth.load();
            const uint32_t presentationHeight=reportPresentationHeight.load();
            if(internalWidth==0||presentationWidth==0)return {};
            char text[128];
            if(internalWidth>presentationWidth&&internalHeight>presentationHeight)
                snprintf(text,sizeof(text),"Supersampled to: %u × %u",internalWidth,internalHeight);
            else
                snprintf(text,sizeof(text),"Upscaled to: %u × %u",presentationWidth,presentationHeight);
            return text;
        }

        // One frame as moonlight-common-c delivered it: its bytes and, per RTP packet, whether it was lost
        // and whether it starts a record (see pyrowaveframing.h). Same steps as the official client's
        // PyroWaveDecoder::decode.
        int submit(const uint8_t *data, size_t length, const std::vector<PyroWaveFraming::Segment> &segments,
                   size_t criticalPackets) {
            enterStage(STAGE_PARSE);
            std::string error;
            const PyroWaveFraming::StreamGeometry geometry = {int(width), int(height), chroma444};
            if (!PyroWaveFraming::parse(data, length, segments, criticalPackets, geometry, parsed, error)) {
                LOGW("Dropping frame: %s", error.c_str());
                return SUBMIT_SKIPPED;
            }
            // Independent reconstruction also prevents stale sequence state after dropped frames
            pyrowave_decoder_clear(decoder);
            for (const auto &span : parsed.spans) {
                if (pyrowave_decoder_push_packet(decoder, data + span.offset, span.size) != PYROWAVE_SUCCESS) {
                    LOGW("Dropping frame: the decoder rejected a packet");
                    return SUBMIT_SKIPPED;
                }
            }
            if (!parsed.partial) {
                if (!pyrowave_decoder_decode_is_ready(decoder, false)) {
                    return SUBMIT_SKIPPED;
                }
            } else {
                // A partial frame decodes whenever its coarsest wavelet level is intact: every other loss
                // only blurs the area it covered.
                if (!parsed.coarseLevelIntact || !pyrowave_decoder_decode_is_ready(decoder, true)) {
                    return SUBMIT_SKIPPED;
                }
            }
            return present() ? SUBMIT_OK : SUBMIT_ERROR;
        }

#if defined(LC_DEBUG)
        // Exercise production allocation, mode application and dispatch without receiving a stream.
        // The independent offscreen probe checks pixel values; this checks actual renderer transitions.
        std::string probeUpscalingLifecycle() {
            auto clear = reinterpret_cast<PFN_vkCmdClearColorImage>(vk.GetDeviceProcAddr(device, "vkCmdClearColorImage"));
            if (clear == nullptr) return "FAIL\nMissing vkCmdClearColorImage\n";
            std::string report = "PASS\nProduction renderer mode/HDR lifecycle:\n";
            for (bool hdr : {false, true, false}) {
                setHdrMode(hdr);
                if (hdrWanted != hdrActive && !applyHdrMode()) return "FAIL\nProduction HDR rebuild\n";
                for (int mode : {1, 2, 3, 4, 3, 0, 4, 2, 0}) {
                    // Repeat each level to exercise accumulated history and optical-flow dispatch,
                    // not only the reset frame immediately after resource creation.
                    for (int level : {1, 1, 2, 2}) {
                        setUpscalingMode(mode);
                        setSupersamplingLevel(level);
                        setSharpness(level == 1 ? 0.0f : 0.85f);
                        applyUpscalingSettings();
                        if (getUpscalingMode() != mode) return "FAIL\nProduction applied mode mismatch\n";
                        if (mode != 0 && !isSupersampling()) return "FAIL\nProduction supersampling missing\n";
                        if (mode == 0 && isSupersampling()) return "FAIL\nOff reported supersampling\n";
                        if (mode != 0 && sgsrOutputWidth != (level == 2 ? width * 2 : (width * 4 / 3 & ~1u)))
                            return "FAIL\nProduction supersampling target mismatch\n";
                        if (mode == 0 && sgsrHistory[0].image != VK_NULL_HANDLE)
                            return "FAIL\nOff retained reconstruction resources\n";
                        if (!check(vk.ResetCommandBuffer(commandBuffer, 0), "probe reset")) return "FAIL\nProbe reset\n";
                        VkCommandBufferBeginInfo begin = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
                        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                        if (!check(vk.BeginCommandBuffer(commandBuffer, &begin), "probe begin")) return "FAIL\nProbe begin\n";
                        planeBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT |
                                VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                planesInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED);
                        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                        for (int plane = 0; plane < 3; ++plane) {
                            VkClearColorValue value = {{plane == 0 ? 0.45f : 0.5f, 0, 0, 0}};
                            clear(commandBuffer, planes[plane].image, VK_IMAGE_LAYOUT_GENERAL, &value, 1, &range);
                        }
                        planesInitialized = true;
                        planeBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL);
                        const int output = recordSgsrMotion(commandBuffer);
                        if ((output >= 0) != (mode != 0)) return "FAIL\nProduction dispatch bypass mismatch\n";
                        if (!check(vk.EndCommandBuffer(commandBuffer), "probe end")) return "FAIL\nProbe end\n";
                        VkSubmitInfo submit = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
                        submit.commandBufferCount = 1;
                        submit.pCommandBuffers = &commandBuffer;
                        vk.ResetFences(device, 1, &frameFence);
                        if (!check(vk.QueueSubmit(queue, 1, &submit, frameFence), "probe submit") ||
                                !check(vk.WaitForFences(device, 1, &frameFence, VK_TRUE, FENCE_TIMEOUT_NS), "probe fence"))
                            return "FAIL\nProduction dispatch submission\n";
                    }
                }
                report += hdr ? "HDR: " : "SDR: ";
                report += "all four modes, both levels, reset/history frames, sharpness 0/85%, Off resource/bypass verified\n";
            }
            return report;
        }
#endif

    private:
        bool check(VkResult result, const char *what) {
            if (result != VK_SUCCESS) {
                LOGE("%s failed: %d", what, result);
                return false;
            }
            return true;
        }

        bool createInstanceAndSurface() {
            if (!vk.loadGlobal()) {
                return false;
            }
            uint32_t loaderVersion = 0;
            vk.EnumerateInstanceVersion(&loaderVersion);
            if (loaderVersion < VK_API_VERSION_1_3) {
                LOGE("Vulkan loader %u.%u is older than 1.3", VK_API_VERSION_MAJOR(loaderVersion), VK_API_VERSION_MINOR(loaderVersion));
                return false;
            }

            // These create infos stay alive for the device's lifetime: PyroWave reads them.
            appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
            appInfo.pApplicationName = "Delos";
            appInfo.apiVersion = VK_API_VERSION_1_3;
            instanceExtensions.assign(std::begin(INSTANCE_EXTENSIONS), std::end(INSTANCE_EXTENSIONS));
            {
                uint32_t count = 0;
                vk.EnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
                std::vector<VkExtensionProperties> available(count);
                vk.EnumerateInstanceExtensionProperties(nullptr, &count, available.data());
                for (const auto &extension : available) {
                    if (std::strcmp(extension.extensionName, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME) == 0) {
                        instanceExtensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
                        colorSpaceExtension = true;
                    }
                }
            }
            instanceInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
            instanceInfo.pApplicationInfo = &appInfo;
            instanceInfo.enabledExtensionCount = uint32_t(instanceExtensions.size());
            instanceInfo.ppEnabledExtensionNames = instanceExtensions.data();
            if (!check(vk.CreateInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance")) {
                return false;
            }
            if (!vk.loadInstance(instance)) {
                LOGE("Vulkan instance is missing required functions");
                // destroy() must not call through missing pointers.
                auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(vk.GetInstanceProcAddr(instance, "vkDestroyInstance"));
                if (destroyInstance != nullptr) {
                    destroyInstance(instance, nullptr);
                }
                instance = VK_NULL_HANDLE;
                return false;
            }

            VkAndroidSurfaceCreateInfoKHR surfaceInfo = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
            surfaceInfo.window = window;
            return check(vk.CreateAndroidSurfaceKHR(instance, &surfaceInfo, nullptr, &surface), "vkCreateAndroidSurfaceKHR");
        }

        bool createDevice() {
            uint32_t count = 0;
            vk.EnumeratePhysicalDevices(instance, &count, nullptr);
            std::vector<VkPhysicalDevice> devices(count);
            vk.EnumeratePhysicalDevices(instance, &count, devices.data());

            FeatureProbe chosenProbe;
            for (auto device : devices) {
                auto probe = probeFeatures(vk, device);
                if (!probe.ok) {
                    LOGI("Skipping %s: missing the PyroWave feature set", probe.name);
                    continue;
                }
                uint32_t familyCount = 0;
                vk.GetPhysicalDeviceQueueFamilyProperties(device, &familyCount, nullptr);
                std::vector<VkQueueFamilyProperties> families(familyCount);
                vk.GetPhysicalDeviceQueueFamilyProperties(device, &familyCount, families.data());
                for (uint32_t i = 0; i < familyCount; ++i) {
                    VkBool32 presentable = VK_FALSE;
                    vk.GetPhysicalDeviceSurfaceSupportKHR(device, i, surface, &presentable);
                    if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentable) {
                        physicalDevice = device;
                        queueFamily = i;
                        timestampsSupported = families[i].timestampValidBits > 0;
                        chosenProbe = probe;
                        break;
                    }
                }
                if (physicalDevice != VK_NULL_HANDLE) {
                    break;
                }
            }
            if (physicalDevice == VK_NULL_HANDLE) {
                LOGE("No Vulkan 1.3 device can decode PyroWave and present to this surface");
                return false;
            }

            queuePriority = 1.0f;
            queueInfo = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            queueInfo.queueFamilyIndex = queueFamily;
            queueInfo.queueCount = 1;
            queueInfo.pQueuePriorities = &queuePriority;

            features13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
            features13.synchronization2 = VK_TRUE;
            features13.subgroupSizeControl = VK_TRUE;
            features13.computeFullSubgroups = VK_TRUE;
            features12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
            features12.pNext = &features13;
            features12.timelineSemaphore = VK_TRUE;
            features12.storageBuffer8BitAccess = VK_TRUE;
            features12.shaderFloat16 = chosenProbe.float16 ? VK_TRUE : VK_FALSE;
            // Delos: Granite (inside PyroWave) reads the enabled features from this create info. Without host
            // query resets it resets its timestamp queries in the command buffer, also inside the fragment
            // path's render passes, where Adreno now and then leaves one reset but never written; the next
            // frame then waits for it forever (the stream froze after a burst of packet loss). Granite's own
            // devices, which the official client uses, enable it too.
            features12.hostQueryReset = chosenProbe.hostQueryReset ? VK_TRUE : VK_FALSE;
            features2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
            features2.pNext = &features12;
            features2.features.shaderInt16 = VK_TRUE;

            deviceInfo = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
            deviceInfo.pNext = &features2;
            deviceInfo.queueCreateInfoCount = 1;
            deviceInfo.pQueueCreateInfos = &queueInfo;
            deviceExtensions.assign(std::begin(DEVICE_EXTENSIONS), std::end(DEVICE_EXTENSIONS));
            {
                uint32_t count = 0;
                vk.EnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, nullptr);
                std::vector<VkExtensionProperties> available(count);
                vk.EnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, available.data());
                for (const auto &extension : available) {
                    if (std::strcmp(extension.extensionName, VK_EXT_HDR_METADATA_EXTENSION_NAME) == 0) {
                        deviceExtensions.push_back(VK_EXT_HDR_METADATA_EXTENSION_NAME);
                        hdrMetadataExtension = true;
                    }
                    if (std::strcmp(extension.extensionName, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) == 0) {
                        deviceExtensions.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
                        hardwareBufferExtension = true;
                    }
                    if (std::strcmp(extension.extensionName, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0) {
                        deviceExtensions.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
                        foreignQueueExtension = true;
                    }
                }
            }
            deviceInfo.enabledExtensionCount = uint32_t(deviceExtensions.size());
            deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();
            if (!check(vk.CreateDevice(physicalDevice, &deviceInfo, nullptr, &device), "vkCreateDevice")) {
                return false;
            }
            if (!vk.loadDevice(device)) {
                LOGE("Vulkan device is missing required functions");
                auto destroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(vk.GetDeviceProcAddr(device, "vkDestroyDevice"));
                if (destroyDevice != nullptr) {
                    destroyDevice(device, nullptr);
                }
                device = VK_NULL_HANDLE;
                return false;
            }
            vk.GetDeviceQueue(device, queueFamily, 0, &queue);
            if (hdrMetadataExtension) {
                setHdrMetadata = reinterpret_cast<PFN_vkSetHdrMetadataEXT>(vk.GetDeviceProcAddr(device, "vkSetHdrMetadataEXT"));
            }
            if (hardwareBufferExtension) {
                getHardwareBufferProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                    vk.GetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
            }
            yuvSupported = !chroma444 && tenBit && getHardwareBufferProperties != nullptr && surfaceApi.load();
            LOGI("P010 HDR output %s", yuvSupported ? "available" : "unavailable");
            LOGI("Colour space extension %s, HDR metadata %s", colorSpaceExtension ? "on" : "off",
                 setHdrMetadata != nullptr ? "on" : "off");
            VkPhysicalDeviceProperties props;
            vk.GetPhysicalDeviceProperties(physicalDevice, &props);
            timestampPeriodNs = props.limits.timestampPeriod;

            LOGI("%s on Vulkan %u.%u (float16: %d, host query reset: %d)", chosenProbe.name,
                 VK_API_VERSION_MAJOR(chosenProbe.apiVersion), VK_API_VERSION_MINOR(chosenProbe.apiVersion),
                 chosenProbe.float16, chosenProbe.hostQueryReset);
            return true;
        }

        bool createDecoder() {
            // Debug override for PyroWave's wavelet precision (0 = FP16, 1 = default mixed,
            // 2 = FP32), e.g. `adb shell setprop debug.pyrowave.precision 0`. PyroWave reads it
            // once per process, so restart the app after changing it.
            char precision[PROP_VALUE_MAX] = {};
            if (__system_property_get("debug.pyrowave.precision", precision) > 0) {
                setenv("PYROWAVE_PRECISION", precision, 1);
                LOGI("PyroWave precision override: %s", precision);
            }

            pyrowave_device_create_queue_info pyroQueue = {queue, queueFamily, 0};
            pyrowave_device_create_info info = {};
            info.GetInstanceProcAddr = vk.GetInstanceProcAddr;
            info.instance = instance;
            info.physical_device = physicalDevice;
            info.device = device;
            info.instance_create_info = &instanceInfo;
            info.device_create_info = &deviceInfo;
            info.queue_info = &pyroQueue;
            info.queue_info_count = 1;
            // All submissions happen on the decode thread, so no queue lock is needed.
            auto result = pyrowave_create_device(&info, &pyroDevice);
            if (result != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_create_device failed: %d", result);
                return false;
            }
            // Mobile GPUs (Adreno, Mali) decode much faster with PyroWave's fragment path,
            // which runs the inverse DWT in render passes instead of compute shaders.
            fragmentPath = pyrowave_decoder_device_prefers_fragment_path(pyroDevice);
            // Debug override: `adb shell setprop debug.pyrowave.path compute|fragment`.
            char path[PROP_VALUE_MAX] = {};
            if (__system_property_get("debug.pyrowave.path", path) > 0) {
                if (strcmp(path, "compute") == 0) {
                    fragmentPath = false;
                } else if (strcmp(path, "fragment") == 0) {
                    fragmentPath = true;
                }
                LOGI("PyroWave decode path override: %s", path);
            }
            // Command buffers are recorded for the graphics queue, which also does compute.
            pyrowave_device_set_queue_type(pyroDevice, fragmentPath ? VK_QUEUE_GRAPHICS_BIT : VK_QUEUE_COMPUTE_BIT);

            pyrowave_decoder_create_info decoderInfo = {};
            decoderInfo.device = pyroDevice;
            decoderInfo.width = int(width);
            decoderInfo.height = int(height);
            decoderInfo.chroma = chroma444 ? PYROWAVE_CHROMA_SUBSAMPLING_444 : PYROWAVE_CHROMA_SUBSAMPLING_420;
            // The decoder writes the planes (as storage images or render targets) and the
            // CSC pass samples them directly.
            decoderInfo.fragment_path = fragmentPath;
            LOGI("Using the PyroWave %s decode path", fragmentPath ? "fragment" : "compute");
            result = pyrowave_decoder_create(&decoderInfo, &decoder);
            if (result != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_decoder_create failed: %d", result);
                return false;
            }
            return true;
        }

        bool createPlane(Plane &plane, uint32_t planeWidth, uint32_t planeHeight) {
            plane.width = planeWidth;
            plane.height = planeHeight;

            VkImageCreateInfo imageInfo = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = planeFormat;
            imageInfo.extent = {planeWidth, planeHeight, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = (fragmentPath ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT : VK_IMAGE_USAGE_STORAGE_BIT) |
                              VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
#if defined(LC_DEBUG)
            imageInfo.usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
#endif
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            if (!check(vk.CreateImage(device, &imageInfo, nullptr, &plane.image), "vkCreateImage")) {
                return false;
            }

            VkMemoryRequirements requirements;
            vk.GetImageMemoryRequirements(device, plane.image, &requirements);
            VkPhysicalDeviceMemoryProperties memoryProps;
            vk.GetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProps);
            uint32_t typeIndex = UINT32_MAX;
            for (uint32_t i = 0; i < memoryProps.memoryTypeCount; ++i) {
                if ((requirements.memoryTypeBits & (1u << i)) &&
                    (memoryProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                    typeIndex = i;
                    break;
                }
            }
            if (typeIndex == UINT32_MAX) {
                LOGE("No device-local memory for a plane image");
                return false;
            }
            VkMemoryAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocInfo.allocationSize = requirements.size;
            allocInfo.memoryTypeIndex = typeIndex;
            if (!check(vk.AllocateMemory(device, &allocInfo, nullptr, &plane.memory), "vkAllocateMemory") ||
                !check(vk.BindImageMemory(device, plane.image, plane.memory, 0), "vkBindImageMemory")) {
                return false;
            }

            VkImageViewCreateInfo viewInfo = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = plane.image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = planeFormat;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            return check(vk.CreateImageView(device, &viewInfo, nullptr, &plane.view), "vkCreateImageView");
        }

        bool createPlanes() {
            // The CSC shader samples chroma with normalised coordinates, so full-size
            // (4:4:4) planes need no shader change; its siting offset turns itself off.
            const uint32_t chromaWidth = chroma444 ? width : width / 2;
            const uint32_t chromaHeight = chroma444 ? height : height / 2;
            return createPlane(planes[0], width, height) &&
                   createPlane(planes[1], chromaWidth, chromaHeight) &&
                   createPlane(planes[2], chromaWidth, chromaHeight);
        }

        bool createAuxImage(Plane &image, VkFormat format, uint32_t imageWidth, uint32_t imageHeight,
                            VkImageUsageFlags usage) {
            image.width = imageWidth; image.height = imageHeight;
            VkImageCreateInfo info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            info.imageType = VK_IMAGE_TYPE_2D; info.format = format;
            info.extent = {imageWidth, imageHeight, 1}; info.mipLevels = 1; info.arrayLayers = 1;
            info.samples = VK_SAMPLE_COUNT_1_BIT; info.tiling = VK_IMAGE_TILING_OPTIMAL;
            info.usage = usage; info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            if (!check(vk.CreateImage(device, &info, nullptr, &image.image), "SGSR image")) return false;
            VkMemoryRequirements requirements; vk.GetImageMemoryRequirements(device, image.image, &requirements);
            VkPhysicalDeviceMemoryProperties memory; vk.GetPhysicalDeviceMemoryProperties(physicalDevice, &memory);
            uint32_t type = UINT32_MAX;
            for (uint32_t i=0;i<memory.memoryTypeCount;i++) if ((requirements.memoryTypeBits&(1u<<i)) &&
                    (memory.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {type=i;break;}
            if (type==UINT32_MAX) return false;
            VkMemoryAllocateInfo allocation={VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocation.allocationSize=requirements.size;allocation.memoryTypeIndex=type;
            if(!check(vk.AllocateMemory(device,&allocation,nullptr,&image.memory),"SGSR memory")||
               !check(vk.BindImageMemory(device,image.image,image.memory,0),"SGSR bind")) return false;
            VkImageViewCreateInfo view={VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            view.image=image.image;view.viewType=VK_IMAGE_VIEW_TYPE_2D;view.format=format;
            view.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            return check(vk.CreateImageView(device,&view,nullptr,&image.view),"SGSR view");
        }

        bool createComputePipeline(const uint32_t *code, size_t bytes, VkPipelineLayout layout,
                                   VkPipeline &result) {
            VkShaderModule module = createShader(code, bytes);
            if (module == VK_NULL_HANDLE) return false;
            VkComputePipelineCreateInfo info = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            info.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            info.stage.module = module;
            info.stage.pName = "main";
            info.layout = layout;
            const bool ok = check(vk.CreateComputePipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &result),
                                  "upscaler compute pipeline");
            vk.DestroyShaderModule(device, module, nullptr);
            return ok;
        }

        bool createComputeLayout(uint32_t count, uint32_t storageBinding, uint32_t pushBytes,
                                 VkDescriptorSetLayout &layout, VkPipelineLayout &pipelineLayout) {
            VkDescriptorSetLayoutBinding bindings[8] = {};
            for (uint32_t i = 0; i < count; ++i) {
                bindings[i] = {i, i == storageBinding ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE :
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
            }
            VkDescriptorSetLayoutCreateInfo info = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            info.bindingCount = count;
            info.pBindings = bindings;
            if (!check(vk.CreateDescriptorSetLayout(device, &info, nullptr, &layout), "upscaler descriptor layout")) return false;
            VkPushConstantRange range = {VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
            VkPipelineLayoutCreateInfo pipelineInfo = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            pipelineInfo.setLayoutCount = 1;
            pipelineInfo.pSetLayouts = &layout;
            pipelineInfo.pushConstantRangeCount = 1;
            pipelineInfo.pPushConstantRanges = &range;
            return check(vk.CreatePipelineLayout(device, &pipelineInfo, nullptr, &pipelineLayout), "upscaler pipeline layout");
        }

        bool allocateImageSet(VkDescriptorSetLayout layout, VkDescriptorSet &set,
                              const VkImageView *views, uint32_t count, int storageBinding) {
            VkDescriptorSetAllocateInfo info = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            info.descriptorPool = sgsrPool;
            info.descriptorSetCount = 1;
            info.pSetLayouts = &layout;
            if (!check(vk.AllocateDescriptorSets(device, &info, &set), "upscaler descriptor set")) return false;
            VkDescriptorImageInfo images[8] = {};
            VkWriteDescriptorSet writes[8] = {};
            for (uint32_t i = 0; i < count; ++i) {
                const bool storage = int(i) == storageBinding;
                images[i] = {storage ? VK_NULL_HANDLE : sgsrSampler, views[i], VK_IMAGE_LAYOUT_GENERAL};
                writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                writes[i].dstSet = set;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[i].pImageInfo = &images[i];
            }
            vk.UpdateDescriptorSets(device, count, writes, 0, nullptr);
            return true;
        }

        bool createSgsrMotion() {
            if (!sgsrApplied) return true;
            const bool temporal = sgsrAppliedMode >= 3;
            const bool quality = sgsrAppliedMode == 4;
            const uint32_t flowWidth = (width + 7) / 8, flowHeight = (height + 7) / 8;
            const float scale = std::min(float(sgsrTargetWidth) / float(width), float(sgsrTargetHeight) / float(height));
            sgsrPresentationWidth = std::max(2u, uint32_t(float(width) * scale)) & ~1u;
            sgsrPresentationHeight = std::max(2u, uint32_t(float(height) * scale)) & ~1u;
            const bool nativeInput = std::abs(int(width) - int(sgsrPresentationWidth)) <= 2 &&
                                     std::abs(int(height) - int(sgsrPresentationHeight)) <= 2;
            const uint32_t numerator = sgsrAppliedLevel >= 2 ? 2u : 4u;
            const uint32_t denominator = sgsrAppliedLevel >= 2 ? 1u : 3u;
            const uint32_t candidateWidth = (sgsrPresentationWidth * numerator / denominator) & ~1u;
            const uint32_t candidateHeight = (sgsrPresentationHeight * numerator / denominator) & ~1u;
            VkPhysicalDeviceProperties properties = {};
            vk.GetPhysicalDeviceProperties(physicalDevice, &properties);
            const bool fits = candidateWidth <= properties.limits.maxImageDimension2D &&
                    candidateHeight <= properties.limits.maxImageDimension2D &&
                    candidateWidth <= 3840 && candidateHeight <= 3840 &&
                    uint64_t(candidateWidth) * candidateHeight <= uint64_t(3840) * 2160;
            sgsrOutputWidth = nativeInput && fits ? candidateWidth : sgsrPresentationWidth;
            sgsrOutputHeight = nativeInput && fits ? candidateHeight : sgsrPresentationHeight;
            reportInternalWidth.store(sgsrOutputWidth);
            reportInternalHeight.store(sgsrOutputHeight);
            reportPresentationWidth.store(sgsrPresentationWidth);
            reportPresentationHeight.store(sgsrPresentationHeight);
            constexpr VkImageUsageFlags sampledStorage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            if (!createAuxImage(sgsrHistory[0], VK_FORMAT_R16G16B16A16_SFLOAT, sgsrOutputWidth, sgsrOutputHeight, sampledStorage)) return false;
            if (temporal && (!createAuxImage(sgsrPrevious, planeFormat, width, height, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT) ||
                    !createAuxImage(sgsrFlow[0], VK_FORMAT_R16G16B16A16_SFLOAT, flowWidth, flowHeight, sampledStorage) ||
                    !createAuxImage(sgsrFlow[1], VK_FORMAT_R16G16B16A16_SFLOAT, flowWidth, flowHeight, sampledStorage) ||
                    !createAuxImage(sgsrHistory[1], VK_FORMAT_R16G16B16A16_SFLOAT, sgsrOutputWidth, sgsrOutputHeight, sampledStorage))) return false;
            if (quality && !createAuxImage(sgsrReactive, VK_FORMAT_R16G16B16A16_SFLOAT, width, height, sampledStorage)) return false;
            VkSamplerCreateInfo samplerInfo = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            samplerInfo.magFilter = VK_FILTER_LINEAR;
            samplerInfo.minFilter = VK_FILTER_LINEAR;
            samplerInfo.addressModeU = samplerInfo.addressModeV = samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            if (!check(vk.CreateSampler(device, &samplerInfo, nullptr, &sgsrSampler), "upscaler sampler")) return false;
            if (!createComputeLayout(8, 6, 48, sgsrUpscaleLayout, sgsrUpscalePipelineLayout)) return false;
            if (temporal) {
                if (!createComputeLayout(3, 2, 16, sgsrFlowLayout, sgsrFlowPipelineLayout) ||
                        !createComputePipeline(sgsr_flow_comp_spv, sizeof(sgsr_flow_comp_spv), sgsrFlowPipelineLayout, sgsrFlowPipeline)) return false;
            }
            if (quality && (!createComputeLayout(5, 4, 48, sgsrActivateLayout, sgsrActivatePipelineLayout) ||
                    !createComputePipeline(sgsr_activate_comp_spv, sizeof(sgsr_activate_comp_spv), sgsrActivatePipelineLayout, sgsrActivatePipeline))) return false;
            if (!createComputePipeline(temporal ? sgsr_upscale_comp_spv : sgsr_spatial_comp_spv,
                    temporal ? sizeof(sgsr_upscale_comp_spv) : sizeof(sgsr_spatial_comp_spv),
                    sgsrUpscalePipelineLayout, sgsrUpscalePipeline)) return false;
            VkDescriptorSetLayoutBinding presentBinding = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
            VkDescriptorSetLayoutCreateInfo presentLayout = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            presentLayout.bindingCount = 1;
            presentLayout.pBindings = &presentBinding;
            if (!check(vk.CreateDescriptorSetLayout(device, &presentLayout, nullptr, &sgsrPresentLayout), "upscaler present layout")) return false;
            VkPushConstantRange presentRange = {VK_SHADER_STAGE_FRAGMENT_BIT, 0, 8};
            VkPipelineLayoutCreateInfo presentPipelineLayout = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            presentPipelineLayout.setLayoutCount = 1;
            presentPipelineLayout.pSetLayouts = &sgsrPresentLayout;
            presentPipelineLayout.pushConstantRangeCount = 1;
            presentPipelineLayout.pPushConstantRanges = &presentRange;
            if (!check(vk.CreatePipelineLayout(device, &presentPipelineLayout, nullptr, &sgsrPresentPipelineLayout), "upscaler present pipeline layout")) return false;
            VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 24}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 5}};
            VkDescriptorPoolCreateInfo pool = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pool.maxSets = 7;
            pool.poolSizeCount = 2;
            pool.pPoolSizes = sizes;
            if (!check(vk.CreateDescriptorPool(device, &pool, nullptr, &sgsrPool), "upscaler descriptor pool")) return false;
            if (temporal) {
                for (int direction = 0; direction < 2; ++direction) {
                    const VkImageView views[] = {direction == 0 ? planes[0].view : sgsrPrevious.view,
                            direction == 0 ? sgsrPrevious.view : planes[0].view, sgsrFlow[direction].view};
                    if (!allocateImageSet(sgsrFlowLayout, sgsrFlowSets[direction], views, 3, 2)) return false;
                }
            }
            if (quality) {
                const VkImageView views[] = {planes[0].view, sgsrPrevious.view, sgsrFlow[0].view, sgsrFlow[1].view, sgsrReactive.view};
                if (!allocateImageSet(sgsrActivateLayout, sgsrActivateSet, views, 5, 4)) return false;
            }
            for (int output = 0; output < (temporal ? 2 : 1); ++output) {
                const VkImageView views[] = {planes[0].view, planes[1].view, planes[2].view,
                        temporal ? sgsrHistory[1 - output].view : planes[0].view,
                        temporal ? sgsrFlow[0].view : planes[0].view,
                        temporal ? sgsrFlow[1].view : planes[0].view, sgsrHistory[output].view,
                        quality ? sgsrReactive.view : planes[0].view};
                if (!allocateImageSet(sgsrUpscaleLayout, sgsrUpscaleSets[output], views, 8, 6)) return false;
                const VkImageView present[] = {sgsrHistory[output].view};
                if (!allocateImageSet(sgsrPresentLayout, sgsrPresentSets[output], present, 1, -1)) return false;
            }
            sgsrPresentPipeline = buildPipeline(renderPass, sgsr_present_frag_spv, sizeof(sgsr_present_frag_spv), sgsrPresentPipelineLayout);
            if (sgsrPresentPipeline == VK_NULL_HANDLE) return false;
            sgsrInitialized = false;
            LOGI("PyroWave upscaler mode %d ready: %ux%u -> %ux%u -> %ux%u, level %d",
                 sgsrAppliedMode, width, height, sgsrOutputWidth, sgsrOutputHeight,
                 sgsrPresentationWidth, sgsrPresentationHeight, sgsrAppliedLevel);
            return true;
        }

        bool createSwapchain() {
            VkSurfaceCapabilitiesKHR caps;
            if (!check(vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &caps), "surface capabilities")) {
                return false;
            }
            if (caps.currentExtent.width == 0 || caps.currentExtent.height == 0) {
                LOGW("Surface has no extent yet");
                return false;
            }

            if (swapchainFormat == VK_FORMAT_UNDEFINED) {
                uint32_t formatCount = 0;
                vk.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, nullptr);
                std::vector<VkSurfaceFormatKHR> formats(formatCount);
                vk.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, &formatCount, formats.data());
                if (formats.empty()) {
                    LOGE("Surface reports no formats");
                    return false;
                }
                // UNORM, not sRGB: the shader already outputs gamma-encoded BT.709 values.
                auto chosen = formats[0];
                for (const auto &format : formats) {
                    if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                        chosen = format;
                        break;
                    }
                }
                // Delos: HDR10 passes the stream's PQ BT.2020 values straight through (no tone mapping).
                // Android offers the HDR10 colour space only where the display reports wide colour; elsewhere
                // (the Odin) a 10-bit format gets the BT.2020 PQ dataspace set on the window directly, the
                // way MediaCodec's HDR output reaches the compositor.
                pqDataspace = false;
                yuvOutput = false;
                if (hdrActive) {
                    bool found = false;
                    for (const auto &format : formats) {
                        if (format.colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT &&
                                (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                                 format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32)) {
                            chosen = format;
                            found = true;
                            break;
                        }
                    }
                    // The swapchain stays SDR underneath while the P010 layer covers it
                    if (!found && yuvSupported) {
                        yuvOutput = true;
                        found = true;
                    }
                    if (!found && setBuffersDataSpace != nullptr) {
                        for (const auto &format : formats) {
                            if (format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR &&
                                    (format.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
                                     format.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32)) {
                                chosen = format;
                                found = true;
                                pqDataspace = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        LOGW("The surface offers no 10-bit format; HDR shows as SDR");
                        for (const auto &format : formats) {
                            LOGI("Surface format %d, colour space %d", format.format, format.colorSpace);
                        }
                    }
                }
                swapchainFormat = chosen.format;
                swapchainColorSpace = chosen.colorSpace;
                LOGI("Swapchain format %d, colour space %d", swapchainFormat, swapchainColorSpace);
            }

            if (presentMode == VK_PRESENT_MODE_MAX_ENUM_KHR) {
                uint32_t modeCount = 0;
                vk.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &modeCount, nullptr);
                std::vector<VkPresentModeKHR> modes(modeCount);
                vk.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, &modeCount, modes.data());
                // MAILBOX shows the newest frame without tearing where available.
                presentMode = std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end() ?
                    VK_PRESENT_MODE_MAILBOX_KHR : VK_PRESENT_MODE_FIFO_KHR;
            }

            uint32_t imageCount = caps.minImageCount + 1;
            if (caps.maxImageCount > 0) {
                imageCount = std::min(imageCount, caps.maxImageCount);
            }

            VkSwapchainCreateInfoKHR info = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
            info.surface = surface;
            info.minImageCount = imageCount;
            info.imageFormat = swapchainFormat;
            info.imageColorSpace = swapchainColorSpace;
            info.imageExtent = caps.currentExtent;
            info.imageArrayLayers = 1;
            info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
            // The shader draws unrotated; let the compositor rotate like it does for MediaCodec.
            info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ?
                VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
            info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ?
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
            info.presentMode = presentMode;
            info.clipped = VK_TRUE;
            info.oldSwapchain = swapchain;

            VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
            if (!check(vk.CreateSwapchainKHR(device, &info, nullptr, &newSwapchain), "vkCreateSwapchainKHR")) {
                return false;
            }
            destroySwapchainResources();
            if (swapchain != VK_NULL_HANDLE) {
                vk.DestroySwapchainKHR(device, swapchain, nullptr);
            }
            swapchain = newSwapchain;
            swapchainExtent = caps.currentExtent;
            if (pqDataspace) {
                // After the swapchain, which sets the dataspace of its colour space when it is created
                // Without the P010 output (see YUV output). The Odin's display hardware ignores it on RGB
                // layers; other devices may honour it.
                const int32_t status = setBuffersDataSpace(window, ADATASPACE_BT2020_PQ);
                LOGI("BT.2020 PQ dataspace on the window: %d", status);
            }

            uint32_t count = 0;
            vk.GetSwapchainImagesKHR(device, swapchain, &count, nullptr);
            swapchainImages.resize(count);
            vk.GetSwapchainImagesKHR(device, swapchain, &count, swapchainImages.data());
            return renderPass == VK_NULL_HANDLE || createSwapchainResources();
        }

        bool createSwapchainResources() {
            for (auto image : swapchainImages) {
                VkImageViewCreateInfo viewInfo = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
                viewInfo.image = image;
                viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
                viewInfo.format = swapchainFormat;
                viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                VkImageView view;
                if (!check(vk.CreateImageView(device, &viewInfo, nullptr, &view), "swapchain view")) {
                    return false;
                }
                swapchainViews.push_back(view);

                VkFramebufferCreateInfo fbInfo = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
                fbInfo.renderPass = renderPass;
                fbInfo.attachmentCount = 1;
                fbInfo.pAttachments = &view;
                fbInfo.width = swapchainExtent.width;
                fbInfo.height = swapchainExtent.height;
                fbInfo.layers = 1;
                VkFramebuffer framebuffer;
                if (!check(vk.CreateFramebuffer(device, &fbInfo, nullptr, &framebuffer), "vkCreateFramebuffer")) {
                    return false;
                }
                framebuffers.push_back(framebuffer);

                // Per image, so a present never waits on a semaphore a later submit re-signals.
                VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
                VkSemaphore semaphore;
                if (!check(vk.CreateSemaphore(device, &semInfo, nullptr, &semaphore), "vkCreateSemaphore")) {
                    return false;
                }
                renderDone.push_back(semaphore);
            }
            return true;
        }

        void destroySwapchainResources() {
            for (auto framebuffer : framebuffers) {
                vk.DestroyFramebuffer(device, framebuffer, nullptr);
            }
            for (auto view : swapchainViews) {
                vk.DestroyImageView(device, view, nullptr);
            }
            for (auto semaphore : renderDone) {
                vk.DestroySemaphore(device, semaphore, nullptr);
            }
            framebuffers.clear();
            swapchainViews.clear();
            renderDone.clear();
        }

        // Checks at most every SIZE_CHECK_INTERVAL_US whether the surface extent differs
        // from the swapchain's.
        bool surfaceSizeChanged(uint64_t now) {
            constexpr uint64_t SIZE_CHECK_INTERVAL_US = 250'000;
            if (now - lastSizeCheckUs < SIZE_CHECK_INTERVAL_US) {
                return false;
            }
            lastSizeCheckUs = now;
            VkSurfaceCapabilitiesKHR caps;
            if (vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, &caps) != VK_SUCCESS) {
                return false;
            }
            return caps.currentExtent.width != swapchainExtent.width ||
                   caps.currentExtent.height != swapchainExtent.height;
        }

        bool recreateSwapchain() {
            vk.DeviceWaitIdle(device);
            return createSwapchain();
        }

        // Delos: a new swapchain format means a new render pass and pipeline as well
        bool applyHdrMode() {
            vk.DeviceWaitIdle(device);
            hdrActive = hdrWanted;
            csc.matrix = hdrActive ? 2 : sdrMatrix;
            LOGI("HDR %s", hdrActive ? "on" : "off");
            destroySwapchainResources();
            destroyYuvOutput();
            destroySgsrMotion();
            destroyPipeline();
            swapchainFormat = VK_FORMAT_UNDEFINED;
            if (!createSwapchain() || !createPipeline() || !createSgsrMotion()) {
                return false;
            }
            sgsrInitialized = false;
            applyHdrMetadata();
            return true;
        }

        // YUV output. The Odin's display hardware ignores the HDR dataspace of RGB layers (BT.2020 PQ shows
        // like sRGB: grey and flat), while it shows MediaCodec's P010 HDR frames right. So in HDR the decoded
        // planes go into P010 AHardwareBuffers, handed to SurfaceFlinger on a child surface control with the
        // BT2020_ITU_PQ dataspace, exactly what MediaCodec hands it; the display hardware does the YCbCr
        // conversion, scaling and HDR mapping. Filling them: Vulkan packs (Y, Cb, Cr) into an RGBA16F
        // AHardwareBuffer and OpenGL ES copies that into P010 (YuvTargetGl), all on the GPU. Without the GL
        // extensions the planes are read back and the CPU fills the buffers instead.
        bool createYuvOutput() {
            releases = std::make_shared<YuvReleases>();
            releases->api = &surfaceApi;
            releases->control = surfaceApi.createFromWindow(window, "Delos PyroWave HDR");
            if (releases->control == nullptr) {
                LOGE("Cannot create a surface control for the HDR output");
                return false;
            }
            // GPU_SAMPLED_IMAGE everywhere: SurfaceFlinger composes with the GPU under the stream menu's blur
            if (!yuvCpu) {
                if (allocateYuvBuffers(AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                       AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) &&
                        createPackPass() && yuvGl.create(packBuffer, yuvBuffers, YUV_BUFFERS,
                            int(sgsrApplied ? sgsrPresentationWidth : width),
                            int(sgsrApplied ? sgsrPresentationHeight : height))) {
                    LOGI("P010 HDR output filled on the GPU");
                    return true;
                }
                LOGW("P010 HDR output: no GPU copy on this device, the CPU fills the buffers");
                yuvGl.destroy();
                destroyPackPass();
                releaseYuvBuffers();
                if (sgsrApplied) {
                    LOGW("PyroWave upscaling and sharpening require the GPU P010 bridge");
                    sgsrApplied = false;
                    sgsrAppliedMode = 0;
                    reportAppliedMode.store(0);
                }
                yuvCpu = true;
            }
            return allocateYuvBuffers(AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                      AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) &&
                   createReadback();
        }

        bool allocateYuvBuffers(uint64_t usage) {
            AHardwareBuffer_Desc desc = {};
            desc.width = sgsrApplied ? sgsrPresentationWidth : width;
            desc.height = sgsrApplied ? sgsrPresentationHeight : height;
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_YCbCr_P010;
            desc.usage = usage;
            for (auto &buffer : yuvBuffers) {
                if (surfaceApi.allocate(&desc, &buffer) != 0) {
                    LOGE("Cannot allocate a P010 buffer (usage 0x%llx)", (unsigned long long) usage);
                    return false;
                }
            }
            return true;
        }

        void releaseYuvBuffers() {
            for (auto &buffer : yuvBuffers) {
                if (buffer != nullptr) surfaceApi.releaseBuffer(buffer);
                buffer = nullptr;
            }
        }

        // The Vulkan half of the GPU copy: an RGBA16F AHardwareBuffer that OpenGL ES samples
        bool createPackPass() {
            AHardwareBuffer_Desc desc = {};
            const uint32_t outputWidth=sgsrApplied?sgsrPresentationWidth:width;
            const uint32_t outputHeight=sgsrApplied?sgsrPresentationHeight:height;
            desc.width = outputWidth;
            desc.height = outputHeight;
            desc.layers = 1;
            desc.format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
            desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
            if (surfaceApi.allocate(&desc, &packBuffer) != 0) {
                LOGE("Cannot allocate the RGBA16F pack buffer");
                return false;
            }
            VkAndroidHardwareBufferFormatPropertiesANDROID format = {
                VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
            VkAndroidHardwareBufferPropertiesANDROID properties = {
                VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, &format};
            if (!check(getHardwareBufferProperties(device, packBuffer, &properties), "pack buffer properties")) {
                return false;
            }
            if (format.format != VK_FORMAT_R16G16B16A16_SFLOAT) {
                LOGE("The pack buffer imports as format %d", format.format);
                return false;
            }
            VkExternalMemoryImageCreateInfo external = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
            external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
            VkImageCreateInfo imageInfo = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            imageInfo.pNext = &external;
            imageInfo.imageType = VK_IMAGE_TYPE_2D;
            imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            imageInfo.extent = {outputWidth, outputHeight, 1};
            imageInfo.mipLevels = 1;
            imageInfo.arrayLayers = 1;
            imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
            imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
            imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            if (!check(vk.CreateImage(device, &imageInfo, nullptr, &packImage.image), "pack image")) {
                return false;
            }
            VkImportAndroidHardwareBufferInfoANDROID import = {VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
            import.buffer = packBuffer;
            VkMemoryDedicatedAllocateInfo dedicated = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
            dedicated.pNext = &import;
            dedicated.image = packImage.image;
            VkMemoryAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocInfo.pNext = &dedicated;
            allocInfo.allocationSize = properties.allocationSize;
            allocInfo.memoryTypeIndex = uint32_t(__builtin_ctz(properties.memoryTypeBits));
            if (!check(vk.AllocateMemory(device, &allocInfo, nullptr, &packImage.memory), "pack memory") ||
                !check(vk.BindImageMemory(device, packImage.image, packImage.memory, 0), "pack bind")) {
                return false;
            }
            packImage.width = outputWidth;
            packImage.height = outputHeight;
            VkImageViewCreateInfo viewInfo = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            viewInfo.image = packImage.image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (!check(vk.CreateImageView(device, &viewInfo, nullptr, &packImage.view), "pack view")) {
                return false;
            }

            VkAttachmentDescription attachment = {};
            attachment.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            attachment.samples = VK_SAMPLE_COUNT_1_BIT;
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            attachment.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkAttachmentReference colorRef = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
            VkSubpassDescription subpass = {};
            subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = 1;
            subpass.pColorAttachments = &colorRef;
            VkSubpassDependency dependency = {};
            dependency.srcSubpass = 0;
            dependency.dstSubpass = VK_SUBPASS_EXTERNAL;
            dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependency.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dependency.dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            VkRenderPassCreateInfo rpInfo = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
            rpInfo.attachmentCount = 1;
            rpInfo.pAttachments = &attachment;
            rpInfo.subpassCount = 1;
            rpInfo.pSubpasses = &subpass;
            rpInfo.dependencyCount = 1;
            rpInfo.pDependencies = &dependency;
            if (!check(vk.CreateRenderPass(device, &rpInfo, nullptr, &packRenderPass), "pack render pass")) {
                return false;
            }
            VkFramebufferCreateInfo fbInfo = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fbInfo.renderPass = packRenderPass;
            fbInfo.attachmentCount = 1;
            fbInfo.pAttachments = &packImage.view;
            fbInfo.width = outputWidth;
            fbInfo.height = outputHeight;
            fbInfo.layers = 1;
            if (!check(vk.CreateFramebuffer(device, &fbInfo, nullptr, &packFramebuffer), "pack framebuffer")) {
                return false;
            }
            if (sgsrApplied && sgsrPresentPipelineLayout != VK_NULL_HANDLE) {
                packPipeline = buildPipeline(packRenderPass, sgsr_pack_frag_spv, sizeof(sgsr_pack_frag_spv),
                                             sgsrPresentPipelineLayout);
            } else {
                packPipeline = buildPipeline(packRenderPass, ycc_pack_frag_spv, sizeof(ycc_pack_frag_spv), pipelineLayout);
            }
            return packPipeline != VK_NULL_HANDLE;
        }

        void destroyPackPass() {
            if (packPipeline != VK_NULL_HANDLE) vk.DestroyPipeline(device, packPipeline, nullptr);
            if (packFramebuffer != VK_NULL_HANDLE) vk.DestroyFramebuffer(device, packFramebuffer, nullptr);
            if (packRenderPass != VK_NULL_HANDLE) vk.DestroyRenderPass(device, packRenderPass, nullptr);
            if (packImage.view != VK_NULL_HANDLE) vk.DestroyImageView(device, packImage.view, nullptr);
            if (packImage.image != VK_NULL_HANDLE) vk.DestroyImage(device, packImage.image, nullptr);
            if (packImage.memory != VK_NULL_HANDLE) vk.FreeMemory(device, packImage.memory, nullptr);
            if (packBuffer != nullptr) surfaceApi.releaseBuffer(packBuffer);
            packPipeline = VK_NULL_HANDLE;
            packFramebuffer = VK_NULL_HANDLE;
            packRenderPass = VK_NULL_HANDLE;
            packImage = {};
            packBuffer = nullptr;
        }

        // The CPU fallback: the planes are read back and the CPU fills the buffers
        bool createReadback() {
            readbackSize = 0;
            for (auto &plane : planes) {
                readbackSize += VkDeviceSize(plane.width) * plane.height * 2;
            }
            VkBufferCreateInfo info = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
            info.size = readbackSize;
            info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            if (!check(vk.CreateBuffer(device, &info, nullptr, &readbackBuffer), "readback buffer")) {
                return false;
            }
            VkMemoryRequirements requirements;
            vk.GetBufferMemoryRequirements(device, readbackBuffer, &requirements);
            VkPhysicalDeviceMemoryProperties memoryProps;
            vk.GetPhysicalDeviceMemoryProperties(physicalDevice, &memoryProps);
            // Cached memory: the CPU reads every byte
            uint32_t typeIndex = UINT32_MAX;
            for (const VkMemoryPropertyFlags wanted : {VkMemoryPropertyFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT),
                                                       VkMemoryPropertyFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)}) {
                for (uint32_t i = 0; i < memoryProps.memoryTypeCount && typeIndex == UINT32_MAX; ++i) {
                    if ((requirements.memoryTypeBits & (1u << i)) && (memoryProps.memoryTypes[i].propertyFlags & wanted) == wanted) {
                        typeIndex = i;
                    }
                }
            }
            if (typeIndex == UINT32_MAX) {
                return false;
            }
            readbackCoherent = (memoryProps.memoryTypes[typeIndex].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            VkMemoryAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            allocInfo.allocationSize = requirements.size;
            allocInfo.memoryTypeIndex = typeIndex;
            return check(vk.AllocateMemory(device, &allocInfo, nullptr, &readbackMemory), "readback memory") &&
                   check(vk.BindBufferMemory(device, readbackBuffer, readbackMemory, 0), "readback bind") &&
                   check(vk.MapMemory(device, readbackMemory, 0, VK_WHOLE_SIZE, 0, &readbackMapped), "readback map");
        }

        // Fills a locked P010 buffer from the read-back planes (16-bit values, 10 bits in the high bits as
        // the decoder normalises them); returns the unlock fence, or -2 on failure
        int fillYuvBuffer(AHardwareBuffer *buffer) {
            if (!readbackCoherent) {
                VkMappedMemoryRange range = {VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
                range.memory = readbackMemory;
                range.size = VK_WHOLE_SIZE;
                vk.InvalidateMappedMemoryRanges(device, 1, &range);
            }
            AHardwareBuffer_Planes out = {};
            if (surfaceApi.lockPlanes(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &out) != 0 ||
                    out.planeCount < 3) {
                LOGW("Cannot lock the P010 buffer");
                return -2;
            }
            const auto *y = static_cast<const uint8_t *>(readbackMapped);
            const auto *cb = reinterpret_cast<const uint16_t *>(y + size_t(width) * height * 2);
            const auto *cr = cb + size_t(width / 2) * (height / 2);
            for (uint32_t row = 0; row < height; ++row) {
                std::memcpy(static_cast<uint8_t *>(out.planes[0].data) + size_t(row) * out.planes[0].rowStride,
                            y + size_t(row) * width * 2, size_t(width) * 2);
            }
            const uint32_t chromaWidth = width / 2;
            for (uint32_t row = 0; row < height / 2; ++row) {
                auto *u = static_cast<uint8_t *>(out.planes[1].data) + size_t(row) * out.planes[1].rowStride;
                auto *v = static_cast<uint8_t *>(out.planes[2].data) + size_t(row) * out.planes[2].rowStride;
                const uint16_t *cbRow = cb + size_t(row) * chromaWidth;
                const uint16_t *crRow = cr + size_t(row) * chromaWidth;
                for (uint32_t x = 0; x < chromaWidth; ++x) {
                    *reinterpret_cast<uint16_t *>(u + size_t(x) * out.planes[1].pixelStride) = cbRow[x];
                    *reinterpret_cast<uint16_t *>(v + size_t(x) * out.planes[2].pixelStride) = crRow[x];
                }
            }
            int32_t fence = -1;
            surfaceApi.unlock(buffer, &fence);
            return fence;
        }

        void destroyYuvOutput() {
            if (releases != nullptr && releases->control != nullptr) {
                ASurfaceTransaction *transaction = surfaceApi.createTransaction();
                surfaceApi.reparent(transaction, releases->control, nullptr);
                surfaceApi.apply(transaction);
                surfaceApi.deleteTransaction(transaction);
            }
            releases.reset();
            yuvGl.destroy();
            destroyPackPass();
            releaseYuvBuffers();
            if (readbackBuffer != VK_NULL_HANDLE) vk.DestroyBuffer(device, readbackBuffer, nullptr);
            if (readbackMemory != VK_NULL_HANDLE) vk.FreeMemory(device, readbackMemory, nullptr);
            readbackBuffer = VK_NULL_HANDLE;
            readbackMemory = VK_NULL_HANDLE;
            readbackMapped = nullptr;
            yuvShown = false;
            yuvPrevious = -1;
        }

        void hideYuvLayer() {
            if (!yuvShown || releases == nullptr) {
                return;
            }
            ASurfaceTransaction *transaction = surfaceApi.createTransaction();
            surfaceApi.setVisibility(transaction, releases->control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            surfaceApi.apply(transaction);
            surfaceApi.deleteTransaction(transaction);
            yuvShown = false;
        }

        // A P010 buffer SurfaceFlinger does not hold, with its release fence (-1 when none). Should none come
        // back for a while (a callback that never arrives), the oldest one except the newest is taken anyway,
        // so the picture never freezes.
        int takeYuvBuffer(int &fence, uint64_t now) {
            std::lock_guard<std::mutex> lock(releases->lock);
            for (int i = 0; i < YUV_BUFFERS; ++i) {
                const int candidate = (yuvNext + i) % YUV_BUFFERS;
                if (!releases->held[candidate]) {
                    fence = releases->fence[candidate];
                    releases->fence[candidate] = -1;
                    releases->held[candidate] = true;
                    yuvStarvedSinceUs = 0;
                    return candidate;
                }
            }
            if (yuvStarvedSinceUs == 0) {
                yuvStarvedSinceUs = now;
            }
            if (now - yuvStarvedSinceUs < 100'000) {
                return -1;
            }
            int oldest = -1;
            for (int i = 0; i < YUV_BUFFERS; ++i) {
                if (i != yuvPrevious && (oldest < 0 || yuvSubmittedUs[i] < yuvSubmittedUs[oldest])) {
                    oldest = i;
                }
            }
            yuvForcedReleases++;
            LOGW("P010 output: no buffer came back for 100 ms, reusing buffer %d (%u times so far)", oldest,
                 yuvForcedReleases);
            fence = releases->fence[oldest];
            releases->fence[oldest] = -1;
            yuvStarvedSinceUs = 0;
            return oldest;
        }

        // Puts the decoded frame into a free P010 buffer and hands it to SurfaceFlinger
        bool presentYuv(VkCommandBufferBeginInfo &beginInfo, uint64_t frameStart, uint64_t afterFence) {
            if (releases == nullptr && !createYuvOutput()) {
                LOGW("P010 HDR output failed; falling back to the RGB swapchain");
                destroyYuvOutput();
                yuvSupported = false;
                hdrWanted = hdrActive;
                return applyHdrMode();
            }
            int fence = -1;
            enterStage(STAGE_YUV_BUFFER);
            const int index = takeYuvBuffer(fence, frameStart);
            if (index < 0) {
                // SurfaceFlinger still holds every buffer; the next frame replaces this one anyway
                yuvSkipped++;
                return true;
            }
            yuvNext = (index + 1) % YUV_BUFFERS;
            if (fence >= 0) {
                enterStage(STAGE_RELEASE_FENCE);
                pollfd wait = {fence, POLLIN, 0};
                poll(&wait, 1, 100);
                close(fence);
            }
            if (!yuvCpu) {
                enterStage(STAGE_GL_SOURCE);
                yuvGl.waitSourceFree();
            }

            vk.ResetCommandBuffer(commandBuffer, 0);
            vk.BeginCommandBuffer(commandBuffer, &beginInfo);
            planeBarrier(commandBuffer, decodeWriteStage(), decodeWriteAccess(),
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL);
            const int sgsrOutput=recordSgsrMotion(commandBuffer);
            if (yuvCpu) {
                VkDeviceSize offset = 0;
                for (auto &plane : planes) {
                    VkBufferImageCopy region = {};
                    region.bufferOffset = offset;
                    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.imageExtent = {plane.width, plane.height, 1};
                    vk.CmdCopyImageToBuffer(commandBuffer, plane.image, VK_IMAGE_LAYOUT_GENERAL, readbackBuffer, 1, &region);
                    offset += VkDeviceSize(plane.width) * plane.height * 2;
                }
                VkBufferMemoryBarrier toHost = {VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
                toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
                toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                toHost.buffer = readbackBuffer;
                toHost.size = VK_WHOLE_SIZE;
                vk.CmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                                      0, nullptr, 1, &toHost, 0, nullptr);
            } else {
                VkRenderPassBeginInfo rpBegin = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
                rpBegin.renderPass = packRenderPass;
                rpBegin.framebuffer = packFramebuffer;
                const uint32_t outputWidth=sgsrOutput>=0?sgsrPresentationWidth:width;
                const uint32_t outputHeight=sgsrOutput>=0?sgsrPresentationHeight:height;
                rpBegin.renderArea = {{0, 0}, {outputWidth, outputHeight}};
                vk.CmdBeginRenderPass(commandBuffer, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
                VkViewport viewport = {0.0f, 0.0f, float(outputWidth), float(outputHeight), 0.0f, 1.0f};
                VkRect2D scissor = {{0, 0}, {outputWidth, outputHeight}};
                vk.CmdSetViewport(commandBuffer, 0, 1, &viewport);
                vk.CmdSetScissor(commandBuffer, 0, 1, &scissor);
                vk.CmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, packPipeline);
                const VkPipelineLayout activeLayout=sgsrOutput>=0?sgsrPresentPipelineLayout:pipelineLayout;
                const VkDescriptorSet activeSet=sgsrOutput>=0?sgsrPresentSets[sgsrOutput]:descriptorSet;
                vk.CmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, activeLayout, 0, 1,
                                         &activeSet, 0, nullptr);
                if (sgsrOutput >= 0) {
                    const PresentParams params = {sgsrAppliedSharpness, hdrActive ? 1 : 0};
                    vk.CmdPushConstants(commandBuffer, activeLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params), &params);
                } else {
                    vk.CmdPushConstants(commandBuffer, activeLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(csc), &csc);
                }
                vk.CmdDraw(commandBuffer, 3, 1, 0, 0);
                vk.CmdEndRenderPass(commandBuffer);
                // OpenGL ES reads it next
                VkImageMemoryBarrier toForeign = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                toForeign.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                toForeign.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                toForeign.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                toForeign.srcQueueFamilyIndex = foreignQueueExtension ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
                toForeign.dstQueueFamilyIndex = foreignQueueExtension ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
                toForeign.image = packImage.image;
                toForeign.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vk.CmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &toForeign);
            }
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 2);
            }
            if (!check(vk.EndCommandBuffer(commandBuffer), "vkEndCommandBuffer(P010)")) {
                return false;
            }
            VkSubmitInfo submitInfo = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers = &commandBuffer;
            vk.ResetFences(device, 1, &frameFence);
            enterStage(STAGE_PACK_FENCE);
            if (!check(vk.QueueSubmit(queue, 1, &submitInfo, frameFence), "vkQueueSubmit(P010)")) {
                return false;
            }
            queriesPending = queryPool != VK_NULL_HANDLE;
            // The decode is done before OpenGL ES or the CPU uses its result
            if (!check(vk.WaitForFences(device, 1, &frameFence, VK_TRUE, FENCE_TIMEOUT_NS), "P010 fence")) {
                return false;
            }
            enterStage(STAGE_COPY);
            const int acquireFence = yuvCpu ? fillYuvBuffer(yuvBuffers[index]) : yuvGl.draw(index);
            if (acquireFence == -2) {
                std::lock_guard<std::mutex> lock(releases->lock);
                releases->held[index] = false;
                return true;
            }
            const uint64_t afterAcquire = nowUs();

            enterStage(STAGE_TRANSACTION);
            ASurfaceTransaction *transaction = surfaceApi.createTransaction();
            surfaceApi.setBuffer(transaction, releases->control, yuvBuffers[index], acquireFence);
            surfaceApi.setDataSpace(transaction, releases->control, ADATASPACE_BT2020_ITU_PQ);
            if (!yuvShown) {
                // Fit the stream into the window, preserving its aspect ratio
                const float scale = std::min(float(swapchainExtent.width) / float(width),
                                             float(swapchainExtent.height) / float(height));
                const int32_t w = int32_t(float(width) * scale);
                const int32_t h = int32_t(float(height) * scale);
                const int32_t x = (int32_t(swapchainExtent.width) - w) / 2;
                const int32_t y = (int32_t(swapchainExtent.height) - h) / 2;
                const ARect source = {0, 0, int32_t(sgsrOutput>=0?sgsrPresentationWidth:width),
                                      int32_t(sgsrOutput>=0?sgsrPresentationHeight:height)};
                const ARect destination = {x, y, x + w, y + h};
                surfaceApi.setGeometry(transaction, releases->control, source, destination, 0);
                surfaceApi.setZOrder(transaction, releases->control, 1);
                surfaceApi.setTransparency(transaction, releases->control, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
                surfaceApi.setVisibility(transaction, releases->control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
                yuvShown = true;
                LOGI("P010 HDR output on, %dx%d at %d,%d", w, h, x, y);
            }
            surfaceApi.setOnComplete(transaction, new YuvCallback{releases, yuvPrevious}, onYuvTransactionComplete);
            surfaceApi.apply(transaction);
            surfaceApi.deleteTransaction(transaction);
            yuvPrevious = index;
            yuvSubmittedUs[index] = afterAcquire;

            const uint64_t frameEnd = nowUs();
            stats.frames++;
            stats.fenceWaitUs += afterFence - frameStart;
            // "wait swapchain image" in the stats log: here the Vulkan pack and the GL or CPU copy
            stats.acquireWaitUs += afterAcquire - afterFence;
            stats.presentUs += frameEnd - afterAcquire;
            logStatsIfDue(frameEnd);
            return true;
        }

        // The fixed-function state the planes' passes share; [pass] decides the target format
        VkPipeline buildPipeline(VkRenderPass pass, const uint32_t *fragCode, size_t fragSize,
                                 VkPipelineLayout layout) {
            VkShaderModule vert = createShader(fullscreen_vert_spv, sizeof(fullscreen_vert_spv));
            VkShaderModule frag = createShader(fragCode, fragSize);
            VkPipeline result = VK_NULL_HANDLE;
            if (vert != VK_NULL_HANDLE && frag != VK_NULL_HANDLE) {
                VkPipelineShaderStageCreateInfo stages[2] = {};
                stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
                stages[0].module = vert;
                stages[0].pName = "main";
                stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
                stages[1].module = frag;
                stages[1].pName = "main";
                VkPipelineVertexInputStateCreateInfo vertexInput = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
                VkPipelineInputAssemblyStateCreateInfo inputAssembly = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
                inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
                VkPipelineViewportStateCreateInfo viewportState = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
                viewportState.viewportCount = 1;
                viewportState.scissorCount = 1;
                VkPipelineRasterizationStateCreateInfo raster = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
                raster.polygonMode = VK_POLYGON_MODE_FILL;
                raster.cullMode = VK_CULL_MODE_NONE;
                raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
                raster.lineWidth = 1.0f;
                VkPipelineMultisampleStateCreateInfo multisample = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
                multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
                VkPipelineColorBlendAttachmentState blendAttachment = {};
                blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                                 VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
                VkPipelineColorBlendStateCreateInfo blend = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
                blend.attachmentCount = 1;
                blend.pAttachments = &blendAttachment;
                const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
                VkPipelineDynamicStateCreateInfo dynamic = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
                dynamic.dynamicStateCount = 2;
                dynamic.pDynamicStates = dynamicStates;
                VkGraphicsPipelineCreateInfo pipelineInfo = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
                pipelineInfo.stageCount = 2;
                pipelineInfo.pStages = stages;
                pipelineInfo.pVertexInputState = &vertexInput;
                pipelineInfo.pInputAssemblyState = &inputAssembly;
                pipelineInfo.pViewportState = &viewportState;
                pipelineInfo.pRasterizationState = &raster;
                pipelineInfo.pMultisampleState = &multisample;
                pipelineInfo.pColorBlendState = &blend;
                pipelineInfo.pDynamicState = &dynamic;
                pipelineInfo.layout = layout;
                pipelineInfo.renderPass = pass;
                check(vk.CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &result),
                      "vkCreateGraphicsPipelines");
            }
            if (vert != VK_NULL_HANDLE) vk.DestroyShaderModule(device, vert, nullptr);
            if (frag != VK_NULL_HANDLE) vk.DestroyShaderModule(device, frag, nullptr);
            return result;
        }

        // The host's mastering metadata for an HDR10 swapchain. Not with the window dataspace (the Odin): its
        // display then tone maps the picture flat and grey, while MediaCodec's HDR output carries none.
        void applyHdrMetadata() {
            SS_HDR_METADATA source;
            if (setHdrMetadata == nullptr || !hdrActive || swapchainColorSpace != VK_COLOR_SPACE_HDR10_ST2084_EXT ||
                    !LiGetHdrMetadata(&source)) {
                return;
            }
            const auto point = [](uint16_t x, uint16_t y) { return VkXYColorEXT{x / 50000.0f, y / 50000.0f}; };
            VkHdrMetadataEXT metadata = {VK_STRUCTURE_TYPE_HDR_METADATA_EXT};
            metadata.displayPrimaryRed = point(source.displayPrimaries[0].x, source.displayPrimaries[0].y);
            metadata.displayPrimaryGreen = point(source.displayPrimaries[1].x, source.displayPrimaries[1].y);
            metadata.displayPrimaryBlue = point(source.displayPrimaries[2].x, source.displayPrimaries[2].y);
            metadata.whitePoint = point(source.whitePoint.x, source.whitePoint.y);
            metadata.maxLuminance = float(source.maxDisplayLuminance);
            metadata.minLuminance = source.minDisplayLuminance / 10000.0f;
            metadata.maxContentLightLevel = float(source.maxContentLightLevel);
            metadata.maxFrameAverageLightLevel = float(source.maxFrameAverageLightLevel);
            setHdrMetadata(device, 1, &swapchain, &metadata);
            LOGI("HDR metadata: max %u nits, min %.4f nits, MaxCLL %u, MaxFALL %u", source.maxDisplayLuminance,
                 metadata.minLuminance, source.maxContentLightLevel, source.maxFrameAverageLightLevel);
        }

        void destroyPipeline() {
            // The YUV output's pass uses this layout and these descriptors
            destroyYuvOutput();
            if (pipeline != VK_NULL_HANDLE) vk.DestroyPipeline(device, pipeline, nullptr);
            if (descriptorPool != VK_NULL_HANDLE) vk.DestroyDescriptorPool(device, descriptorPool, nullptr);
            if (pipelineLayout != VK_NULL_HANDLE) vk.DestroyPipelineLayout(device, pipelineLayout, nullptr);
            if (setLayout != VK_NULL_HANDLE) vk.DestroyDescriptorSetLayout(device, setLayout, nullptr);
            if (sampler != VK_NULL_HANDLE) vk.DestroySampler(device, sampler, nullptr);
            if (renderPass != VK_NULL_HANDLE) vk.DestroyRenderPass(device, renderPass, nullptr);
            pipeline = VK_NULL_HANDLE;
            descriptorPool = VK_NULL_HANDLE;
            descriptorSet = VK_NULL_HANDLE;
            pipelineLayout = VK_NULL_HANDLE;
            setLayout = VK_NULL_HANDLE;
            sampler = VK_NULL_HANDLE;
            renderPass = VK_NULL_HANDLE;
        }

        VkShaderModule createShader(const uint32_t *code, size_t size) {
            VkShaderModuleCreateInfo info = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            info.codeSize = size;
            info.pCode = code;
            VkShaderModule module = VK_NULL_HANDLE;
            check(vk.CreateShaderModule(device, &info, nullptr, &module), "vkCreateShaderModule");
            return module;
        }

        bool createPipeline() {
            VkAttachmentDescription attachment = {};
            attachment.format = swapchainFormat;
            attachment.samples = VK_SAMPLE_COUNT_1_BIT;
            attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;  // Black letterbox bars.
            attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            VkAttachmentReference colorRef = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
            VkSubpassDescription subpass = {};
            subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = 1;
            subpass.pColorAttachments = &colorRef;
            // Order the layout transition after the acquire semaphore wait.
            VkSubpassDependency dependency = {};
            dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
            dependency.dstSubpass = 0;
            dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            VkRenderPassCreateInfo rpInfo = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
            rpInfo.attachmentCount = 1;
            rpInfo.pAttachments = &attachment;
            rpInfo.subpassCount = 1;
            rpInfo.pSubpasses = &subpass;
            rpInfo.dependencyCount = 1;
            rpInfo.pDependencies = &dependency;
            if (!check(vk.CreateRenderPass(device, &rpInfo, nullptr, &renderPass), "vkCreateRenderPass")) {
                return false;
            }

            VkSamplerCreateInfo samplerInfo = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            samplerInfo.magFilter = VK_FILTER_LINEAR;
            samplerInfo.minFilter = VK_FILTER_LINEAR;
            samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            samplerInfo.maxLod = 0.0f;
            if (!check(vk.CreateSampler(device, &samplerInfo, nullptr, &sampler), "vkCreateSampler")) {
                return false;
            }

            VkDescriptorSetLayoutBinding bindings[3] = {};
            for (uint32_t i = 0; i < 3; ++i) {
                bindings[i].binding = i;
                bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                bindings[i].descriptorCount = 1;
                bindings[i].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
            layoutInfo.bindingCount = 3;
            layoutInfo.pBindings = bindings;
            if (!check(vk.CreateDescriptorSetLayout(device, &layoutInfo, nullptr, &setLayout), "vkCreateDescriptorSetLayout")) {
                return false;
            }
            VkPushConstantRange cscRange = {VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(CscParams)};
            VkPipelineLayoutCreateInfo plInfo = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
            plInfo.setLayoutCount = 1;
            plInfo.pSetLayouts = &setLayout;
            plInfo.pushConstantRangeCount = 1;
            plInfo.pPushConstantRanges = &cscRange;
            if (!check(vk.CreatePipelineLayout(device, &plInfo, nullptr, &pipelineLayout), "vkCreatePipelineLayout")) {
                return false;
            }

            VkDescriptorPoolSize poolSize = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3};
            VkDescriptorPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            if (!check(vk.CreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool), "vkCreateDescriptorPool")) {
                return false;
            }
            VkDescriptorSetAllocateInfo setInfo = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            setInfo.descriptorPool = descriptorPool;
            setInfo.descriptorSetCount = 1;
            setInfo.pSetLayouts = &setLayout;
            if (!check(vk.AllocateDescriptorSets(device, &setInfo, &descriptorSet), "vkAllocateDescriptorSets")) {
                return false;
            }
            // The plane images never change, so the descriptors are written once.
            VkDescriptorImageInfo imageInfos[3];
            VkWriteDescriptorSet writes[3] = {};
            for (uint32_t i = 0; i < 3; ++i) {
                imageInfos[i] = {sampler, planes[i].view, VK_IMAGE_LAYOUT_GENERAL};
                writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[i].dstSet = descriptorSet;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[i].pImageInfo = &imageInfos[i];
            }
            vk.UpdateDescriptorSets(device, 3, writes, 0, nullptr);

            VkShaderModule vert = createShader(fullscreen_vert_spv, sizeof(fullscreen_vert_spv));
            VkShaderModule frag = createShader(planar_csc_frag_spv, sizeof(planar_csc_frag_spv));
            if (vert == VK_NULL_HANDLE || frag == VK_NULL_HANDLE) {
                if (vert != VK_NULL_HANDLE) vk.DestroyShaderModule(device, vert, nullptr);
                if (frag != VK_NULL_HANDLE) vk.DestroyShaderModule(device, frag, nullptr);
                return false;
            }
            VkPipelineShaderStageCreateInfo stages[2] = {};
            stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
            stages[0].module = vert;
            stages[0].pName = "main";
            stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
            stages[1].module = frag;
            stages[1].pName = "main";

            VkPipelineVertexInputStateCreateInfo vertexInput = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VkPipelineViewportStateCreateInfo viewportState = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
            viewportState.viewportCount = 1;
            viewportState.scissorCount = 1;
            VkPipelineRasterizationStateCreateInfo raster = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
            raster.polygonMode = VK_POLYGON_MODE_FILL;
            raster.cullMode = VK_CULL_MODE_NONE;
            raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
            raster.lineWidth = 1.0f;
            VkPipelineMultisampleStateCreateInfo multisample = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
            multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
            VkPipelineColorBlendAttachmentState blendAttachment = {};
            blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                             VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
            VkPipelineColorBlendStateCreateInfo blend = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
            blend.attachmentCount = 1;
            blend.pAttachments = &blendAttachment;
            const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
            VkPipelineDynamicStateCreateInfo dynamic = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
            dynamic.dynamicStateCount = 2;
            dynamic.pDynamicStates = dynamicStates;

            VkGraphicsPipelineCreateInfo pipelineInfo = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
            pipelineInfo.stageCount = 2;
            pipelineInfo.pStages = stages;
            pipelineInfo.pVertexInputState = &vertexInput;
            pipelineInfo.pInputAssemblyState = &inputAssembly;
            pipelineInfo.pViewportState = &viewportState;
            pipelineInfo.pRasterizationState = &raster;
            pipelineInfo.pMultisampleState = &multisample;
            pipelineInfo.pColorBlendState = &blend;
            pipelineInfo.pDynamicState = &dynamic;
            pipelineInfo.layout = pipelineLayout;
            pipelineInfo.renderPass = renderPass;
            const bool ok = check(vk.CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline),
                                  "vkCreateGraphicsPipelines");
            vk.DestroyShaderModule(device, vert, nullptr);
            vk.DestroyShaderModule(device, frag, nullptr);
            return ok && createSwapchainResources();
        }

        bool createFrameResources() {
            VkCommandPoolCreateInfo poolInfo = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
            poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
            poolInfo.queueFamilyIndex = queueFamily;
            if (!check(vk.CreateCommandPool(device, &poolInfo, nullptr, &commandPool), "vkCreateCommandPool")) {
                return false;
            }
            VkCommandBufferAllocateInfo allocInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            allocInfo.commandPool = commandPool;
            allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            allocInfo.commandBufferCount = 2;
            VkCommandBuffer buffers[2];
            if (!check(vk.AllocateCommandBuffers(device, &allocInfo, buffers), "vkAllocateCommandBuffers")) {
                return false;
            }
            decodeCommandBuffer = buffers[0];
            commandBuffer = buffers[1];
            if (timestampsSupported) {
                VkQueryPoolCreateInfo queryInfo = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
                queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
                queryInfo.queryCount = QUERY_COUNT;
                if (vk.CreateQueryPool(device, &queryInfo, nullptr, &queryPool) != VK_SUCCESS) {
                    queryPool = VK_NULL_HANDLE;
                }
            }
            LOGI("Present mode %s, GPU timestamps %s", presentModeName(presentMode),
                 queryPool != VK_NULL_HANDLE ? "on" : "off");
            VkFenceCreateInfo fenceInfo = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            VkSemaphoreCreateInfo semInfo = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            return check(vk.CreateFence(device, &fenceInfo, nullptr, &frameFence), "vkCreateFence") &&
                   check(vk.CreateSemaphore(device, &semInfo, nullptr, &acquireSemaphore), "vkCreateSemaphore");
        }

        // Where the decoder writes the planes: compute storage writes, or colour
        // attachment writes for the fragment path.
        VkPipelineStageFlags decodeWriteStage() const {
            return fragmentPath ? VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        }

        VkAccessFlags decodeWriteAccess() const {
            return fragmentPath ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
        }

        void planeBarrier(VkCommandBuffer cmd, VkPipelineStageFlags srcStage, VkAccessFlags srcAccess, VkPipelineStageFlags dstStage,
                          VkAccessFlags dstAccess, VkImageLayout oldLayout) {
            VkImageMemoryBarrier barriers[3] = {};
            for (int i = 0; i < 3; ++i) {
                barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                barriers[i].srcAccessMask = srcAccess;
                barriers[i].dstAccessMask = dstAccess;
                barriers[i].oldLayout = oldLayout;
                barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barriers[i].image = planes[i].image;
                barriers[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            }
            vk.CmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 3, barriers);
        }

        int recordSgsrMotion(VkCommandBuffer cmd) {
            if (!sgsrApplied || !sgsrAvailable || sgsrUpscalePipeline == VK_NULL_HANDLE) return -1;
            const bool temporal = sgsrAppliedMode >= 3;
            const uint32_t flowWidth=(width+7)/8,flowHeight=(height+7)/8;
            VkMemoryBarrier reuse = {VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            reuse.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            reuse.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &reuse, 0, nullptr, 0, nullptr);
            if (!sgsrInitialized) {
                Plane *images[] = {&sgsrPrevious, &sgsrFlow[0], &sgsrFlow[1], &sgsrHistory[0], &sgsrHistory[1], &sgsrReactive};
                VkImageMemoryBarrier init[6] = {};
                uint32_t count = 0;
                for (Plane *image : images) {
                    if (image->image == VK_NULL_HANDLE) continue;
                    auto &barrier = init[count++];
                    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                    barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                    barrier.image = image->image;
                    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                }
                vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT |
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, count, init);
            } else if (temporal) {
                struct Params{int32_t input[2],flow[2];}params={{int32_t(width),int32_t(height)},{int32_t(flowWidth),int32_t(flowHeight)}};
                vk.CmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,sgsrFlowPipeline);
                for(int direction=0;direction<2;direction++){
                    vk.CmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,sgsrFlowPipelineLayout,0,1,&sgsrFlowSets[direction],0,nullptr);
                    vk.CmdPushConstants(cmd,sgsrFlowPipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(params),&params);
                    vk.CmdDispatch(cmd,(flowWidth+7)/8,(flowHeight+7)/8,1);
                }
            }
            if (temporal && sgsrInitialized) {
                VkImageMemoryBarrier flowReady[2]={};
                for(int i=0;i<2;i++){flowReady[i].sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;flowReady[i].srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
                    flowReady[i].dstAccessMask=VK_ACCESS_SHADER_READ_BIT;flowReady[i].oldLayout=flowReady[i].newLayout=VK_IMAGE_LAYOUT_GENERAL;
                    flowReady[i].srcQueueFamilyIndex=flowReady[i].dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;flowReady[i].image=sgsrFlow[i].image;
                    flowReady[i].subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};}
                vk.CmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,0,nullptr,2,flowReady);
            }
            const int output = temporal ? sgsrHistoryIndex ^ 1 : 0;
            struct UpscaleParams{int32_t input[2],output[2],flow[2],reset,fullRange,matrix,sixteenBit,mode,padding;} params={
                {int32_t(width),int32_t(height)},{int32_t(sgsrOutputWidth),int32_t(sgsrOutputHeight)},
                {int32_t(flowWidth),int32_t(flowHeight)},sgsrInitialized?0:1,csc.fullRange,csc.matrix,csc.sixteenBit,sgsrAppliedMode,0};
            if (sgsrAppliedMode == 4) {
                vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sgsrActivatePipeline);
                vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sgsrActivatePipelineLayout, 0, 1, &sgsrActivateSet, 0, nullptr);
                vk.CmdPushConstants(cmd, sgsrActivatePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
                vk.CmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
                VkImageMemoryBarrier ready = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                ready.oldLayout = ready.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                ready.srcQueueFamilyIndex = ready.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                ready.image = sgsrReactive.image;
                ready.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, 0, nullptr, 0, nullptr, 1, &ready);
            }
            vk.CmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,sgsrUpscalePipeline);
            vk.CmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,sgsrUpscalePipelineLayout,0,1,&sgsrUpscaleSets[output],0,nullptr);
            vk.CmdPushConstants(cmd,sgsrUpscalePipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(params),&params);
            vk.CmdDispatch(cmd,(sgsrOutputWidth+7)/8,(sgsrOutputHeight+7)/8,1);
            VkImageMemoryBarrier historyReady={VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};historyReady.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
            historyReady.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;historyReady.oldLayout=historyReady.newLayout=VK_IMAGE_LAYOUT_GENERAL;
            historyReady.srcQueueFamilyIndex=historyReady.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;historyReady.image=sgsrHistory[output].image;
            historyReady.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            vk.CmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,0,nullptr,0,nullptr,1,&historyReady);
            if (!temporal) {
                sgsrInitialized = true;
                return output;
            }
            VkImageMemoryBarrier toCopy[2]={};
            toCopy[0].sType=toCopy[1].sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toCopy[0].srcAccessMask=VK_ACCESS_SHADER_READ_BIT;toCopy[0].dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;
            toCopy[0].oldLayout=VK_IMAGE_LAYOUT_GENERAL;toCopy[0].newLayout=VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;toCopy[0].image=planes[0].image;
            toCopy[1].srcAccessMask=sgsrInitialized?VK_ACCESS_SHADER_READ_BIT:0;toCopy[1].dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            toCopy[1].oldLayout=VK_IMAGE_LAYOUT_GENERAL;toCopy[1].newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;toCopy[1].image=sgsrPrevious.image;
            for(auto &barrier:toCopy){barrier.srcQueueFamilyIndex=barrier.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
                barrier.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};}
            vk.CmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                                  0,0,nullptr,0,nullptr,2,toCopy);
            VkImageCopy copy={};copy.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.dstSubresource=copy.srcSubresource;copy.extent={width,height,1};
            vk.CmdCopyImage(cmd,planes[0].image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,sgsrPrevious.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
            std::swap(toCopy[0].oldLayout,toCopy[0].newLayout);std::swap(toCopy[1].oldLayout,toCopy[1].newLayout);
            toCopy[0].srcAccessMask=VK_ACCESS_TRANSFER_READ_BIT;toCopy[0].dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            toCopy[1].srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;toCopy[1].dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            vk.CmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                  0,0,nullptr,0,nullptr,2,toCopy);sgsrInitialized=true;sgsrHistoryIndex=output;return output;
        }

        void applyUpscalingSettings() {
            const bool processingSupported = sgsrAvailable && !(yuvOutput && yuvCpu);
            const int requestedMode = processingSupported ? sgsrMode.load() : 0;
            const float requestedSharpness = processingSupported ? sgsrSharpness.load() : 0.0f;
            const int requestedLevel = sgsrLevel.load();
            const bool requestedProcessing = requestedMode != 0;
            const bool recreate = requestedProcessing != sgsrApplied ||
                    (requestedMode >= 3) != (sgsrAppliedMode >= 3) ||
                    (requestedMode == 4) != (sgsrAppliedMode == 4) ||
                    (requestedProcessing && requestedLevel != sgsrAppliedLevel);
            const bool resetHistory = requestedMode != sgsrAppliedMode;
            if (recreate) {
                if (yuvOutput) destroyYuvOutput();
                destroySgsrMotion();
            }
            sgsrApplied = requestedProcessing;
            sgsrAppliedMode = requestedMode;
            sgsrAppliedSharpness = requestedSharpness;
            sgsrAppliedLevel = requestedLevel;
            if (recreate) {
                bool created = createSgsrMotion();
                if (!created && requestedProcessing && requestedLevel > 1) {
                    LOGW("PyroWave supersampling level 2 unavailable; falling back to level 1");
                    destroySgsrMotion();
                    sgsrAppliedLevel = 1;
                    sgsrLevel.store(1);
                    created = createSgsrMotion();
                }
                if (!created) {
                    LOGW("PyroWave upscaler recreation failed; direct rendering remains active");
                    destroySgsrMotion();
                    sgsrAvailable = false;
                    sgsrApplied = false;
                    sgsrAppliedMode = 0;
                }
            }
            if (recreate || resetHistory) sgsrInitialized = false;
            reportAppliedMode.store(sgsrAppliedMode);
        }

        bool present() {
            const uint64_t frameStart = nowUs();

            // One frame in flight: after this wait the previous frame's sampling of the
            // planes is finished, so decoding may overwrite them.
            enterStage(STAGE_FRAME_FENCE);
            if (!check(vk.WaitForFences(device, 1, &frameFence, VK_TRUE, FENCE_TIMEOUT_NS), "frame fence")) {
                return false;
            }
            const uint64_t afterFence = nowUs();
            readTimestamps();
            enterStage(STAGE_HDR_SWITCH);
            if (hdrWanted != hdrActive && !applyHdrMode()) {
                return false;
            }
            applyUpscalingSettings();
            enterStage(STAGE_DECODE);

            // Decode first, without waiting for the display, so the GPU starts on the
            // frame immediately. The draw below is ordered after it on the same queue.
            vk.ResetCommandBuffer(decodeCommandBuffer, 0);
            VkCommandBufferBeginInfo beginInfo = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vk.BeginCommandBuffer(decodeCommandBuffer, &beginInfo);
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdResetQueryPool(decodeCommandBuffer, queryPool, 0, QUERY_COUNT);
                vk.CmdWriteTimestamp(decodeCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);
            }
            planeBarrier(decodeCommandBuffer, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, decodeWriteStage(),
                         decodeWriteAccess(), planesInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED);

            pyrowave_gpu_buffers buffers = {};
            for (int i = 0; i < 3; ++i) {
                auto &view = buffers.planes[i];
                view.image = planes[i].image;
                view.width = planes[i].width;
                view.height = planes[i].height;
                view.image_format = planeFormat;
                view.view_format = planeFormat;
                view.aspect = VK_IMAGE_ASPECT_COLOR_BIT;
                view.swizzle = VK_COMPONENT_SWIZZLE_IDENTITY;
                view.layout = VK_IMAGE_LAYOUT_GENERAL;
            }
            pyrowave_device_set_command_buffer(pyroDevice, decodeCommandBuffer);
            const auto decoded = pyrowave_decoder_decode_gpu_buffer(decoder, nullptr, nullptr, &buffers);
            pyrowave_device_set_command_buffer(pyroDevice, VK_NULL_HANDLE);
            if (decoded != PYROWAVE_SUCCESS) {
                LOGE("pyrowave_decoder_decode_gpu_buffer failed: %d", decoded);
                vk.EndCommandBuffer(decodeCommandBuffer);
                return false;
            }
            planesInitialized = true;
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdWriteTimestamp(decodeCommandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
            }
            if (!check(vk.EndCommandBuffer(decodeCommandBuffer), "vkEndCommandBuffer(decode)")) {
                return false;
            }
            VkSubmitInfo decodeSubmit = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
            decodeSubmit.commandBufferCount = 1;
            decodeSubmit.pCommandBuffers = &decodeCommandBuffer;
            if (!check(vk.QueueSubmit(queue, 1, &decodeSubmit, VK_NULL_HANDLE), "vkQueueSubmit(decode)")) {
                return false;
            }

            if (yuvOutput) {
                return presentYuv(beginInfo, frameStart, afterFence);
            }

            uint32_t imageIndex = 0;
            enterStage(STAGE_ACQUIRE);
            const uint64_t beforeAcquire = nowUs();
            auto acquired = vk.AcquireNextImageKHR(device, swapchain, ACQUIRE_TIMEOUT_NS, acquireSemaphore,
                                                   VK_NULL_HANDLE, &imageIndex);
            const uint64_t afterAcquire = nowUs();
            if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
                return recreateSwapchain();
            }
            if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) {
                return true;  // Skip presenting this frame; the next one replaces it anyway.
            }
            if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
                return check(acquired, "vkAcquireNextImageKHR");
            }

            vk.ResetCommandBuffer(commandBuffer, 0);
            vk.BeginCommandBuffer(commandBuffer, &beginInfo);
            // Barriers order against all earlier work on the queue, which covers the
            // decode submitted above.
            planeBarrier(commandBuffer, decodeWriteStage(), decodeWriteAccess(),
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL);
            const int sgsrOutput=recordSgsrMotion(commandBuffer);

            VkClearValue clear = {};
            clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
            VkRenderPassBeginInfo rpBegin = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
            rpBegin.renderPass = renderPass;
            rpBegin.framebuffer = framebuffers[imageIndex];
            rpBegin.renderArea = {{0, 0}, swapchainExtent};
            rpBegin.clearValueCount = 1;
            rpBegin.pClearValues = &clear;
            vk.CmdBeginRenderPass(commandBuffer, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Fit the stream into the surface, preserving its aspect ratio.
            const float scale = std::min(float(swapchainExtent.width) / float(width),
                                         float(swapchainExtent.height) / float(height));
            VkViewport viewport = {};
            viewport.width = float(width) * scale;
            viewport.height = float(height) * scale;
            viewport.x = (float(swapchainExtent.width) - viewport.width) / 2.0f;
            viewport.y = (float(swapchainExtent.height) - viewport.height) / 2.0f;
            viewport.maxDepth = 1.0f;
            VkRect2D scissor = {{0, 0}, swapchainExtent};
            vk.CmdSetViewport(commandBuffer, 0, 1, &viewport);
            vk.CmdSetScissor(commandBuffer, 0, 1, &scissor);
            const bool useSgsr=sgsrOutput>=0;
            vk.CmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,useSgsr?sgsrPresentPipeline:pipeline);
            const VkPipelineLayout activeLayout=useSgsr?sgsrPresentPipelineLayout:pipelineLayout;
            const VkDescriptorSet activeSet=useSgsr?sgsrPresentSets[sgsrOutput]:descriptorSet;
            vk.CmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,activeLayout,0,1,&activeSet,0,nullptr);
            if(!useSgsr)vk.CmdPushConstants(commandBuffer,pipelineLayout,VK_SHADER_STAGE_FRAGMENT_BIT,0,sizeof(CscParams),&csc);
            else {
                const PresentParams params = {sgsrAppliedSharpness, hdrActive ? 1 : 0};
                vk.CmdPushConstants(commandBuffer, activeLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params), &params);
            }
            vk.CmdDraw(commandBuffer, 3, 1, 0, 0);
            vk.CmdEndRenderPass(commandBuffer);
            if (queryPool != VK_NULL_HANDLE) {
                vk.CmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 2);
            }
            if (!check(vk.EndCommandBuffer(commandBuffer), "vkEndCommandBuffer")) {
                return false;
            }

            const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            VkSubmitInfo submitInfo = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submitInfo.waitSemaphoreCount = 1;
            submitInfo.pWaitSemaphores = &acquireSemaphore;
            submitInfo.pWaitDstStageMask = &waitStage;
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers = &commandBuffer;
            submitInfo.signalSemaphoreCount = 1;
            submitInfo.pSignalSemaphores = &renderDone[imageIndex];
            vk.ResetFences(device, 1, &frameFence);
            enterStage(STAGE_SWAPCHAIN_SUBMIT);
            if (!check(vk.QueueSubmit(queue, 1, &submitInfo, frameFence), "vkQueueSubmit")) {
                return false;
            }
            queriesPending = queryPool != VK_NULL_HANDLE;

            VkPresentInfoKHR presentInfo = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            presentInfo.waitSemaphoreCount = 1;
            presentInfo.pWaitSemaphores = &renderDone[imageIndex];
            presentInfo.swapchainCount = 1;
            presentInfo.pSwapchains = &swapchain;
            presentInfo.pImageIndices = &imageIndex;
            enterStage(STAGE_PRESENT);
            const auto presented = vk.QueuePresentKHR(queue, &presentInfo);
            const uint64_t frameEnd = nowUs();

            stats.frames++;
            stats.fenceWaitUs += afterFence - frameStart;
            stats.acquireWaitUs += afterAcquire - beforeAcquire;
            stats.presentUs += frameEnd - afterAcquire;
            logStatsIfDue(frameEnd);

            if (presented == VK_ERROR_OUT_OF_DATE_KHR) {
                return recreateSwapchain();
            }
            // SUBOPTIMAL is reported on every present while the display is rotated, because
            // the swapchain leaves rotation to the compositor (IDENTITY pre-transform). Only
            // a changed surface size needs a new swapchain; recreating per frame stalls the GPU.
            if ((presented == VK_SUBOPTIMAL_KHR || acquired == VK_SUBOPTIMAL_KHR) && surfaceSizeChanged(frameEnd)) {
                return recreateSwapchain();
            }
            return presented == VK_SUBOPTIMAL_KHR || check(presented, "vkQueuePresentKHR");
        }

        void destroy() {
            if (device != VK_NULL_HANDLE) {
                vk.DeviceWaitIdle(device);
            }
            // PyroWave objects before the VkDevice they borrow.
            if (decoder != nullptr) {
                pyrowave_decoder_destroy(decoder);
                decoder = nullptr;
            }
            if (pyroDevice != nullptr) {
                pyrowave_device_destroy(pyroDevice);
                pyroDevice = nullptr;
            }
            if (device != VK_NULL_HANDLE) {
                // The HDR pack pipeline may use SGSR's descriptor layout, and the SDR SGSR pipeline
                // uses the main render pass. Tear down the bridge first, then SGSR, then the pass.
                destroyYuvOutput();
                destroySgsrMotion();
                destroySwapchainResources();
                if (swapchain != VK_NULL_HANDLE) vk.DestroySwapchainKHR(device, swapchain, nullptr);
                if (acquireSemaphore != VK_NULL_HANDLE) vk.DestroySemaphore(device, acquireSemaphore, nullptr);
                if (frameFence != VK_NULL_HANDLE) vk.DestroyFence(device, frameFence, nullptr);
                if (queryPool != VK_NULL_HANDLE) vk.DestroyQueryPool(device, queryPool, nullptr);
                if (commandPool != VK_NULL_HANDLE) vk.DestroyCommandPool(device, commandPool, nullptr);
                destroyPipeline();
                for (auto &plane : planes) {
                    if (plane.view != VK_NULL_HANDLE) vk.DestroyImageView(device, plane.view, nullptr);
                    if (plane.image != VK_NULL_HANDLE) vk.DestroyImage(device, plane.image, nullptr);
                    if (plane.memory != VK_NULL_HANDLE) vk.FreeMemory(device, plane.memory, nullptr);
                }
                vk.DestroyDevice(device, nullptr);
                device = VK_NULL_HANDLE;
            }
            if (instance != VK_NULL_HANDLE) {
                if (surface != VK_NULL_HANDLE) vk.DestroySurfaceKHR(instance, surface, nullptr);
                vk.DestroyInstance(instance, nullptr);
                instance = VK_NULL_HANDLE;
            }
            if (window != nullptr) {
                ANativeWindow_release(window);
                window = nullptr;
            }
        }

        void destroySgsrMotion() {
            if(sgsrPresentPipeline!=VK_NULL_HANDLE)vk.DestroyPipeline(device,sgsrPresentPipeline,nullptr);
            if(sgsrUpscalePipeline!=VK_NULL_HANDLE)vk.DestroyPipeline(device,sgsrUpscalePipeline,nullptr);
            if(sgsrFlowPipeline!=VK_NULL_HANDLE)vk.DestroyPipeline(device,sgsrFlowPipeline,nullptr);
            if(sgsrActivatePipeline!=VK_NULL_HANDLE)vk.DestroyPipeline(device,sgsrActivatePipeline,nullptr);
            if(sgsrActivatePipelineLayout!=VK_NULL_HANDLE)vk.DestroyPipelineLayout(device,sgsrActivatePipelineLayout,nullptr);
            if(sgsrActivateLayout!=VK_NULL_HANDLE)vk.DestroyDescriptorSetLayout(device,sgsrActivateLayout,nullptr);
            if(sgsrPresentPipelineLayout!=VK_NULL_HANDLE)vk.DestroyPipelineLayout(device,sgsrPresentPipelineLayout,nullptr);
            if(sgsrPresentLayout!=VK_NULL_HANDLE)vk.DestroyDescriptorSetLayout(device,sgsrPresentLayout,nullptr);
            if(sgsrUpscalePipelineLayout!=VK_NULL_HANDLE)vk.DestroyPipelineLayout(device,sgsrUpscalePipelineLayout,nullptr);
            if(sgsrUpscaleLayout!=VK_NULL_HANDLE)vk.DestroyDescriptorSetLayout(device,sgsrUpscaleLayout,nullptr);
            if(sgsrFlowPipelineLayout!=VK_NULL_HANDLE)vk.DestroyPipelineLayout(device,sgsrFlowPipelineLayout,nullptr);
            if(sgsrFlowLayout!=VK_NULL_HANDLE)vk.DestroyDescriptorSetLayout(device,sgsrFlowLayout,nullptr);
            if(sgsrPool!=VK_NULL_HANDLE)vk.DestroyDescriptorPool(device,sgsrPool,nullptr);
            if(sgsrSampler!=VK_NULL_HANDLE)vk.DestroySampler(device,sgsrSampler,nullptr);
            for(Plane *image:{&sgsrPrevious,&sgsrFlow[0],&sgsrFlow[1],&sgsrHistory[0],&sgsrHistory[1],&sgsrReactive}){
                if(image->view!=VK_NULL_HANDLE)vk.DestroyImageView(device,image->view,nullptr);
                if(image->image!=VK_NULL_HANDLE)vk.DestroyImage(device,image->image,nullptr);
                if(image->memory!=VK_NULL_HANDLE)vk.FreeMemory(device,image->memory,nullptr);*image={};
            }
            sgsrPresentPipeline=sgsrUpscalePipeline=sgsrFlowPipeline=VK_NULL_HANDLE;
            sgsrPresentPipelineLayout=sgsrUpscalePipelineLayout=sgsrFlowPipelineLayout=VK_NULL_HANDLE;
            sgsrPresentLayout=sgsrUpscaleLayout=sgsrFlowLayout=VK_NULL_HANDLE;
            sgsrPool=VK_NULL_HANDLE;sgsrSampler=VK_NULL_HANDLE;
            sgsrActivatePipeline = VK_NULL_HANDLE;
            sgsrActivatePipelineLayout = VK_NULL_HANDLE;
            sgsrActivateLayout = VK_NULL_HANDLE;
            sgsrInitialized = false;
        }

        VulkanLoader vk;
        ANativeWindow *window = nullptr;
        uint32_t width = 0;
        uint32_t height = 0;
        bool chroma444 = false;
        bool tenBit = false;
        VkFormat planeFormat = VK_FORMAT_R8_UNORM;
        int sdrMatrix = 1;
        CscParams csc = {0, 1, 0};
        // Delos: HDR follows the host (setHdrMode() from Java, applied on the video thread)
        std::atomic<bool> hdrWanted{false};
        bool hdrActive = false;
        bool colorSpaceExtension = false;
        bool hdrMetadataExtension = false;
        PFN_vkSetHdrMetadataEXT setHdrMetadata = nullptr;
        using SetBuffersDataSpace = int32_t (*)(ANativeWindow *, int32_t);
        SetBuffersDataSpace setBuffersDataSpace = nullptr;
        bool pqDataspace = false;
        bool hardwareBufferExtension = false;
        bool foreignQueueExtension = false;
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID getHardwareBufferProperties = nullptr;
        SurfaceApi surfaceApi;
        bool yuvSupported = false;
        bool yuvCpu = false;
        VkBuffer readbackBuffer = VK_NULL_HANDLE;
        VkDeviceMemory readbackMemory = VK_NULL_HANDLE;
        VkDeviceSize readbackSize = 0;
        void *readbackMapped = nullptr;
        bool readbackCoherent = true;
        bool yuvOutput = false;
        bool yuvShown = false;
        int yuvNext = 0;
        int yuvPrevious = -1;
        std::shared_ptr<YuvReleases> releases;
        AHardwareBuffer *yuvBuffers[YUV_BUFFERS] = {};
        uint64_t yuvSubmittedUs[YUV_BUFFERS] = {};
        uint64_t yuvStarvedSinceUs = 0;
        uint32_t yuvForcedReleases = 0;
        uint32_t yuvSkipped = 0;
        YuvTargetGl yuvGl;
        AHardwareBuffer *packBuffer = nullptr;
        Plane packImage;
        VkRenderPass packRenderPass = VK_NULL_HANDLE;
        VkFramebuffer packFramebuffer = VK_NULL_HANDLE;
        VkPipeline packPipeline = VK_NULL_HANDLE;
        std::vector<const char *> instanceExtensions;
        std::vector<const char *> deviceExtensions;
        // Reused between frames, so parsing allocates nothing once warm
        PyroWaveFraming::Frame parsed;

        // Kept alive for PyroWave, which reads the create infos after device creation.
        VkApplicationInfo appInfo = {};
        VkInstanceCreateInfo instanceInfo = {};
        float queuePriority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo = {};
        VkPhysicalDeviceVulkan13Features features13 = {};
        VkPhysicalDeviceVulkan12Features features12 = {};
        VkPhysicalDeviceFeatures2 features2 = {};
        VkDeviceCreateInfo deviceInfo = {};

        VkInstance instance = VK_NULL_HANDLE;
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
        uint32_t queueFamily = 0;
        VkDevice device = VK_NULL_HANDLE;
        VkQueue queue = VK_NULL_HANDLE;

        pyrowave_device pyroDevice = nullptr;
        pyrowave_decoder decoder = nullptr;
        Plane planes[3];
        bool planesInitialized = false;
        bool fragmentPath = false;
        std::atomic<int> sgsrMode{0};
        std::atomic<int> reportAppliedMode{0};
        std::atomic<float> sgsrSharpness{0.0f};
        std::atomic<int> sgsrLevel{1};
        bool sgsrAvailable = false;
        bool sgsrApplied = false;
        int sgsrAppliedMode = 0;
        float sgsrAppliedSharpness = 0.0f;
        int sgsrAppliedLevel = 1;
        bool sgsrInitialized = false;
        uint32_t sgsrTargetWidth = 0;
        uint32_t sgsrTargetHeight = 0;
        Plane sgsrPrevious;
        Plane sgsrFlow[2];
        Plane sgsrHistory[2];
        Plane sgsrReactive;
        uint32_t sgsrOutputWidth = 0;
        uint32_t sgsrOutputHeight = 0;
        uint32_t sgsrPresentationWidth = 0;
        uint32_t sgsrPresentationHeight = 0;
        std::atomic<uint32_t> reportInternalWidth{0};
        std::atomic<uint32_t> reportInternalHeight{0};
        std::atomic<uint32_t> reportPresentationWidth{0};
        std::atomic<uint32_t> reportPresentationHeight{0};
        int sgsrHistoryIndex = 0;
        VkSampler sgsrSampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout sgsrFlowLayout = VK_NULL_HANDLE;
        VkPipelineLayout sgsrFlowPipelineLayout = VK_NULL_HANDLE;
        VkDescriptorPool sgsrPool = VK_NULL_HANDLE;
        VkDescriptorSet sgsrFlowSets[2] = {};
        VkPipeline sgsrFlowPipeline = VK_NULL_HANDLE;
        VkDescriptorSetLayout sgsrUpscaleLayout = VK_NULL_HANDLE;
        VkPipelineLayout sgsrUpscalePipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSet sgsrUpscaleSets[2] = {};
        VkPipeline sgsrUpscalePipeline = VK_NULL_HANDLE;
        VkDescriptorSetLayout sgsrActivateLayout = VK_NULL_HANDLE;
        VkPipelineLayout sgsrActivatePipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSet sgsrActivateSet = VK_NULL_HANDLE;
        VkPipeline sgsrActivatePipeline = VK_NULL_HANDLE;
        VkDescriptorSetLayout sgsrPresentLayout = VK_NULL_HANDLE;
        VkPipelineLayout sgsrPresentPipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSet sgsrPresentSets[2] = {};
        VkPipeline sgsrPresentPipeline = VK_NULL_HANDLE;
        uint64_t lastSizeCheckUs = 0;

        VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;
        VkColorSpaceKHR swapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_MAX_ENUM_KHR;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkExtent2D swapchainExtent = {};
        std::vector<VkImage> swapchainImages;
        std::vector<VkImageView> swapchainViews;
        std::vector<VkFramebuffer> framebuffers;
        std::vector<VkSemaphore> renderDone;

        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;

        VkCommandPool commandPool = VK_NULL_HANDLE;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkFence frameFence = VK_NULL_HANDLE;
        VkSemaphore acquireSemaphore = VK_NULL_HANDLE;

        // Queries 0/1 bracket decode; 1/2 include reconstruction, sharpening and output conversion.
        static constexpr uint32_t QUERY_COUNT = 3;
        static constexpr uint64_t STATS_INTERVAL_US = 5'000'000;
        VkCommandBuffer decodeCommandBuffer = VK_NULL_HANDLE;
        VkQueryPool queryPool = VK_NULL_HANDLE;
        bool timestampsSupported = false;
        int frameRateHz = 60;

        bool queriesPending = false;
        float timestampPeriodNs = 1.0f;

    public:
        // GPU decode time of the most recently completed frame, or 0 when unknown.
        uint32_t lastGpuDecodeUs = 0;
        uint32_t lastGpuDrawUs = 0;

    private:
        struct {
            uint64_t startUs = 0;
            uint32_t frames = 0;
            uint64_t gpuDecodeUs = 0;
            uint64_t gpuDrawUs = 0;
            uint32_t gpuSamples = 0;
            uint64_t fenceWaitUs = 0;
            uint64_t acquireWaitUs = 0;
            uint64_t presentUs = 0;
        } stats;

        void readTimestamps() {
            if (!queriesPending) {
                return;
            }
            queriesPending = false;
            uint64_t ticks[QUERY_COUNT] = {};
            if (vk.GetQueryPoolResults(device, queryPool, 0, QUERY_COUNT, sizeof(ticks), ticks, sizeof(uint64_t),
                                       VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
                return;
            }
            const auto toUs = [this](uint64_t delta) { return uint64_t(double(delta) * timestampPeriodNs / 1000.0); };
            lastGpuDecodeUs = uint32_t(toUs(ticks[1] - ticks[0]));
            lastGpuDrawUs = uint32_t(toUs(ticks[2] - ticks[1]));
            stats.gpuDecodeUs += lastGpuDecodeUs;
            stats.gpuDrawUs += lastGpuDrawUs;
            stats.gpuSamples++;
        }

        void logStatsIfDue(uint64_t now) {
            if (stats.startUs == 0) {
                stats.startUs = now;
                return;
            }
            if (now - stats.startUs < STATS_INTERVAL_US || stats.frames == 0) {
                return;
            }
            const double seconds = double(now - stats.startUs) / 1e6;
            const double gpuFrames = stats.gpuSamples ? double(stats.gpuSamples) : 1.0;
            LOGI("%.1f fps: GPU decode %.2f ms, GPU draw %.2f ms, wait previous frame %.2f ms, "
                 "wait swapchain image %.2f ms, submit+present %.2f ms (%s)",
                 stats.frames / seconds, stats.gpuDecodeUs / gpuFrames / 1000.0, stats.gpuDrawUs / gpuFrames / 1000.0,
                 stats.fenceWaitUs / double(stats.frames) / 1000.0, stats.acquireWaitUs / double(stats.frames) / 1000.0,
                 stats.presentUs / double(stats.frames) / 1000.0, presentModeName(presentMode));
            if (yuvOutput) {
                LOGI("P010 output (%s): %u frames skipped for want of a buffer, %u buffers reused without a release",
                     yuvCpu ? "CPU" : "GPU", yuvSkipped, yuvForcedReleases);
                yuvSkipped = 0;
            }
            stats = {};
            stats.startUs = now;

            // PyroWave's own per-pass GPU timings, to see which stage dominates decode.
            pyrowave_device_report_performance_stats(pyroDevice, [](void *, const char *msg) {
                LOGI("PyroWave pass: %s", msg);
            }, nullptr, true);
        }
    };

    bool probeAvailable() {
        if (!apiVersionSupported()) {
            return false;
        }
        VulkanLoader vk;
        if (!vk.loadGlobal()) {
            return false;
        }
        uint32_t loaderVersion = 0;
        vk.EnumerateInstanceVersion(&loaderVersion);
        if (loaderVersion < VK_API_VERSION_1_3) {
            LOGI("Vulkan loader is older than 1.3; PyroWave unavailable");
            return false;
        }

        VkApplicationInfo appInfo = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
        appInfo.apiVersion = VK_API_VERSION_1_3;
        VkInstanceCreateInfo instanceInfo = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instanceInfo.pApplicationInfo = &appInfo;
        VkInstance instance = VK_NULL_HANDLE;
        if (vk.CreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) {
            return false;
        }
        // Only the functions the probe needs; surface functions may be absent here.
        vk.EnumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
            vk.GetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
        vk.GetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            vk.GetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
        vk.GetPhysicalDeviceFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vk.GetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
        vk.DestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
            vk.GetInstanceProcAddr(instance, "vkDestroyInstance"));

        bool capable = false;
        uint32_t count = 0;
        vk.EnumeratePhysicalDevices(instance, &count, nullptr);
        std::vector<VkPhysicalDevice> devices(count);
        vk.EnumeratePhysicalDevices(instance, &count, devices.data());
        for (auto device : devices) {
            auto probe = probeFeatures(vk, device);
            LOGI("%s (Vulkan %u.%u) PyroWave-capable: %d", probe.name, VK_API_VERSION_MAJOR(probe.apiVersion),
                 VK_API_VERSION_MINOR(probe.apiVersion), probe.ok);
            capable = capable || probe.ok;
        }
        vk.DestroyInstance(instance, nullptr);
        return capable;
    }
}  // namespace

// The renderer of the running stream, which PyroWave_SubmitDecodeUnit feeds on moonlight-common-c's video
// thread; created and destroyed from Java (PyroWaveRenderer).
namespace {
    std::mutex activeLock;
    Renderer *activeRenderer = nullptr;

    // The statistics window for the stream's overlay (PyroWaveRenderer.takeStats), filled per frame
    // like MediaCodecDecoderRenderer's VideoStats for the other codecs
    struct WindowStats {
        uint32_t totalFrames = 0;
        uint32_t receivedFrames = 0;
        uint32_t renderedFrames = 0;
        uint32_t lostFrames = 0;
        uint32_t partialFrames = 0;
        uint32_t hostLatencyFrames = 0;
        uint32_t hostLatencyMin = 0;
        uint32_t hostLatencyMax = 0;
        uint64_t hostLatencyTotal = 0;
        uint32_t gpuDecodeFrames = 0;
        uint64_t gpuDecodeTotalUs = 0;
        uint32_t gpuDrawFrames = 0;
        uint64_t gpuDrawTotalUs = 0;
    };
    std::mutex statsLock;
    WindowStats windowStats;
    int lastFrameNumber = 0;

    // The intervals between presented frames for the 1% low (about ten seconds at 120 FPS); longer
    // gaps are a still picture, not a hitch. Same rules as FrameTimes.java.
    constexpr size_t FRAME_INTERVALS = 1200;
    constexpr uint64_t IDLE_GAP_US = 500'000;
    uint64_t frameIntervals[FRAME_INTERVALS] = {};
    size_t frameIntervalCount = 0;
    size_t frameIntervalNext = 0;
    uint64_t lastPresentUs = 0;

    int64_t onePercentLowFpsX100() {
        if (frameIntervalCount < 100) {
            return -100;
        }
        std::vector<uint64_t> sorted(frameIntervals, frameIntervals + frameIntervalCount);
        std::sort(sorted.begin(), sorted.end());
        const size_t worst = std::max<size_t>(1, frameIntervalCount / 100);
        uint64_t total = 0;
        for (size_t i = frameIntervalCount - worst; i < frameIntervalCount; ++i) {
            total += sorted[i];
        }
        return int64_t(1e8 * double(worst) / double(total));
    }
}

extern "C" {

// Called by moonlight-core's decode unit callback for PyroWave streams: one buffer per RTP packet,
// lost packets zero-filled and typed BUFFER_TYPE_LOST, record starts BUFFER_TYPE_RECORD_START.
int PyroWave_SubmitDecodeUnit(PDECODE_UNIT decodeUnit) {
    lastFrameUs.store(nowUs(), std::memory_order_relaxed);
    videoTid.store(pid_t(syscall(SYS_gettid)), std::memory_order_relaxed);
    static thread_local std::vector<uint8_t> frame;
    static thread_local std::vector<PyroWaveFraming::Segment> segments;
    frame.resize(size_t(decodeUnit->fullLength));
    segments.clear();
    size_t offset = 0;
    for (PLENTRY entry = decodeUnit->bufferList; entry != nullptr; entry = entry->next) {
        if (offset + size_t(entry->length) > frame.size()) {
            frame.resize(offset + size_t(entry->length));
        }
        std::memcpy(frame.data() + offset, entry->data, size_t(entry->length));
        segments.push_back({offset, size_t(entry->length), entry->bufferType == BUFFER_TYPE_LOST,
                            entry->bufferType == BUFFER_TYPE_RECORD_START});
        offset += size_t(entry->length);
    }
    bool partial = false;
    for (const auto &segment : segments) {
        partial |= segment.lost;
    }
    int result = SUBMIT_SKIPPED;
    uint32_t gpuDecodeUs = 0;
    uint32_t gpuDrawUs = 0;
    {
        enterStage(STAGE_LOCK);
        std::lock_guard<std::mutex> lock(activeLock);
        if (activeRenderer != nullptr) {
            result = activeRenderer->submit(frame.data(), offset, segments, decodeUnit->pyrowaveCriticalPackets);
            // The GPU time of the previous frame, read back while this one was submitted
            gpuDecodeUs = activeRenderer->lastGpuDecodeUs;
            gpuDrawUs = activeRenderer->lastGpuDrawUs;
        }
        enterStage(STAGE_IDLE);
    }
    {
        std::lock_guard<std::mutex> lock(statsLock);
        WindowStats &w = windowStats;
        if (lastFrameNumber != 0 && decodeUnit->frameNumber > lastFrameNumber + 1) {
            w.lostFrames += uint32_t(decodeUnit->frameNumber - lastFrameNumber - 1);
            w.totalFrames += uint32_t(decodeUnit->frameNumber - lastFrameNumber - 1);
        }
        lastFrameNumber = decodeUnit->frameNumber;
        w.totalFrames++;
        w.receivedFrames++;
        if (result == SUBMIT_OK) {
            w.renderedFrames++;
            const uint64_t now = nowUs();
            if (lastPresentUs != 0 && now - lastPresentUs < IDLE_GAP_US) {
                frameIntervals[frameIntervalNext] = now - lastPresentUs;
                frameIntervalNext = (frameIntervalNext + 1) % FRAME_INTERVALS;
                frameIntervalCount = std::min(frameIntervalCount + 1, FRAME_INTERVALS);
            }
            lastPresentUs = now;
        }
        if (partial) {
            w.partialFrames++;
        }
        if (decodeUnit->frameHostProcessingLatency != 0) {
            const uint32_t latency = decodeUnit->frameHostProcessingLatency;
            w.hostLatencyMin = w.hostLatencyFrames == 0 ? latency : std::min(w.hostLatencyMin, latency);
            w.hostLatencyMax = std::max(w.hostLatencyMax, latency);
            w.hostLatencyTotal += latency;
            w.hostLatencyFrames++;
        }
        if (gpuDecodeUs != 0) {
            w.gpuDecodeTotalUs += gpuDecodeUs;
            w.gpuDecodeFrames++;
        }
        if (gpuDrawUs != 0) {
            w.gpuDrawTotalUs += gpuDrawUs;
            w.gpuDrawFrames++;
        }
    }
    // Every PyroWave frame is independent: never ask the host for a keyframe
    return DR_OK;
}

#if defined(LC_DEBUG)
JNIEXPORT jstring JNICALL
Java_com_limelight_binding_video_PyroWaveGpuInstrumentation_nativeRunRendererProbe(JNIEnv *env, jclass,
                                                                                 jobject surface) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return env->NewStringUTF("FAIL\nProbe surface unavailable\n");
    Renderer renderer;
    if (!renderer.create(window, 128, 96, 60, 128, 96, false, true, 1, true, 0, 1, 0.0f))
        return env->NewStringUTF("FAIL\nProduction renderer initialization\n");
    const std::string report = renderer.probeUpscalingLifecycle();
    return env->NewStringUTF(report.c_str());
}
#endif

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeIsAvailable(JNIEnv *, jclass) {
    // The capability is a property of the driver, so probe once per process.
    static std::once_flag once;
    static bool available = false;
    std::call_once(once, [] { available = probeAvailable(); });
    return available ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeCreate(JNIEnv *env, jclass, jobject surface, jint width,
                                                               jint height, jint frameRate, jint outputWidth,
                                                               jint outputHeight, jboolean chroma444, jboolean fullRange,
                                                               jint matrix, jboolean tenBit, jint upscalingMode,
                                                               jint supersamplingLevel, jfloat sharpness) {
    if (surface == nullptr || width <= 0 || height <= 0 || (!chroma444 && ((width & 1) || (height & 1)))) {
        LOGE("PyroWave needs a surface and positive dimensions, even for 4:2:0 (%dx%d)", width, height);
        return 0;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return 0;
    }
    installStackSignal();
    auto renderer = std::make_unique<Renderer>();
    if (!renderer->create(window, width, height, frameRate, outputWidth, outputHeight, chroma444, fullRange,
                          matrix, tenBit, upscalingMode, supersamplingLevel, sharpness)) {
        return 0;  // The renderer releases the window.
    }
    LOGI("PyroWave renderer ready for %dx%d %s %s", width, height, chroma444 ? "4:4:4" : "4:2:0",
         tenBit ? "10-bit" : "8-bit");
    {
        std::lock_guard<std::mutex> lock(statsLock);
        windowStats = {};
        lastFrameNumber = 0;
        frameIntervalCount = 0;
        frameIntervalNext = 0;
        lastPresentUs = 0;
    }
    std::lock_guard<std::mutex> lock(activeLock);
    activeRenderer = renderer.get();
    return reinterpret_cast<jlong>(renderer.release());
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetLastGpuDecodeUs(JNIEnv *, jclass, jlong handle) {
    auto *renderer = reinterpret_cast<Renderer *>(handle);
    return renderer != nullptr ? jint(renderer->lastGpuDecodeUs) : 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetHdrMode(JNIEnv *, jclass, jlong handle, jboolean enabled) {
    auto *renderer = reinterpret_cast<Renderer *>(handle);
    if (renderer != nullptr) {
        renderer->setHdrMode(enabled);
    }
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetUpscalingMode(JNIEnv *, jclass, jlong handle,
                                                                      jint mode) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    if(renderer!=nullptr)renderer->setUpscalingMode(mode);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetUpscalingMode(JNIEnv *, jclass, jlong handle) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    return renderer!=nullptr?renderer->getUpscalingMode():0;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetSharpness(JNIEnv *, jclass, jlong handle,
                                                                  jfloat sharpness) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    if(renderer!=nullptr)renderer->setSharpness(sharpness);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeSetSupersamplingLevel(JNIEnv *, jclass, jlong handle,
                                                                               jint level) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    if(renderer!=nullptr)renderer->setSupersamplingLevel(level);
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeIsSupersampling(JNIEnv *, jclass, jlong handle) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    return renderer!=nullptr&&renderer->isSupersampling()?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeGetScaleDescription(JNIEnv *env, jclass, jlong handle) {
    auto *renderer=reinterpret_cast<Renderer *>(handle);
    if(renderer==nullptr)return nullptr;
    const std::string description=renderer->scaleDescription();
    return description.empty()?nullptr:env->NewStringUTF(description.c_str());
}

// Copies the statistics window into [out] (the order of PyroWaveRenderer.Stats) and starts a new one
JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeTakeStats(JNIEnv *env, jclass, jlongArray out) {
    const uint64_t now = nowUs();
    const int stage = videoStage.load(std::memory_order_relaxed);
    const uint64_t since = videoStageSinceUs.load(std::memory_order_relaxed);
    const uint64_t lastFrame = lastFrameUs.load(std::memory_order_relaxed);
    if (stage != STAGE_IDLE && since != 0 && now - since > 500'000) {
        LOGW("Video thread stuck %llu ms: %s", (unsigned long long) ((now - since) / 1000), STAGE_NAMES[stage]);
        // Once per stall, after two seconds: the thread's state, kernel wait and native stack
        static uint64_t dumpedStall = 0;
        const pid_t tid = videoTid.load(std::memory_order_relaxed);
        if (now - since > 2'000'000 && dumpedStall != since && tid != 0) {
            dumpedStall = since;
            std::string stat = readTaskFile(tid, "stat");
            const size_t paren = stat.rfind(')');
            LOGW("Video thread %d state '%s', waiting in '%s'", tid,
                 paren != std::string::npos && paren + 2 < stat.size() ? stat.substr(paren + 2, 1).c_str() : "?",
                 readTaskFile(tid, "wchan").c_str());
            syscall(SYS_tgkill, getpid(), tid, STACK_SIGNAL);
        }
    } else if (lastFrame != 0 && now - lastFrame > 1'000'000) {
        LOGW("No frame from moonlight-common-c for %llu ms", (unsigned long long) ((now - lastFrame) / 1000));
    }
    WindowStats w;
    int64_t onePercentLow;
    {
        std::lock_guard<std::mutex> lock(statsLock);
        w = windowStats;
        windowStats = {};
        onePercentLow = onePercentLowFpsX100();
    }
    const jlong values[] = {w.totalFrames, w.receivedFrames, w.renderedFrames, w.lostFrames, w.partialFrames,
                            w.hostLatencyFrames, w.hostLatencyMin, w.hostLatencyMax, jlong(w.hostLatencyTotal),
                            w.gpuDecodeFrames, jlong(w.gpuDecodeTotalUs), jlong(onePercentLow),
                            w.gpuDrawFrames, jlong(w.gpuDrawTotalUs)};
    env->SetLongArrayRegion(out, 0, std::min<jsize>(env->GetArrayLength(out), jsize(std::size(values))), values);
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_video_PyroWaveRenderer_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    auto *renderer = reinterpret_cast<Renderer *>(handle);
    {
        std::lock_guard<std::mutex> lock(activeLock);
        if (activeRenderer == renderer) {
            activeRenderer = nullptr;
        }
    }
    delete renderer;
}

}  // extern "C"

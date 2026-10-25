# Android.mk for the vendored PyroWave codec (see VENDOR.txt and tools/pyrowave/vendor.ps1).
#
# The source list and defines follow the official Vibepollo client (Nonary/moonlight-qt
# pyrowave/pyrowave.pri). PyroWave needs Vulkan 1.3, so it is built only for the 64-bit ABIs;
# on 32-bit devices Delos never offers it.
LOCAL_PATH := $(call my-dir)

ifneq ($(filter arm64-v8a x86_64,$(TARGET_ARCH_ABI)),)

PW_DIR := pyrowave
GRANITE_DIR := $(PW_DIR)/Granite

include $(CLEAR_VARS)
LOCAL_MODULE := pyrowave

LOCAL_SRC_FILES := \
    $(PW_DIR)/pyrowave_c.cpp \
    $(PW_DIR)/pyrowave_common.cpp \
    $(PW_DIR)/pyrowave_decoder.cpp \
    $(PW_DIR)/pyrowave_encoder.cpp \
    $(GRANITE_DIR)/video/scaler.cpp \
    volk_platform.c \
    $(GRANITE_DIR)/math/aabb.cpp \
    $(GRANITE_DIR)/math/frustum.cpp \
    $(GRANITE_DIR)/math/interpolation.cpp \
    $(GRANITE_DIR)/math/math.cpp \
    $(GRANITE_DIR)/math/muglm/muglm.cpp \
    $(GRANITE_DIR)/math/transforms.cpp \
    $(GRANITE_DIR)/util/aligned_alloc.cpp \
    $(GRANITE_DIR)/util/arena_allocator.cpp \
    $(GRANITE_DIR)/util/cli_parser.cpp \
    $(GRANITE_DIR)/util/dynamic_library.cpp \
    $(GRANITE_DIR)/util/environment.cpp \
    $(GRANITE_DIR)/util/logging.cpp \
    $(GRANITE_DIR)/util/message_queue.cpp \
    $(GRANITE_DIR)/util/slab_allocator.cpp \
    $(GRANITE_DIR)/util/string_helpers.cpp \
    $(GRANITE_DIR)/util/thread_id.cpp \
    $(GRANITE_DIR)/util/thread_name.cpp \
    $(GRANITE_DIR)/util/thread_priority.cpp \
    $(GRANITE_DIR)/util/timeline_trace_file.cpp \
    $(GRANITE_DIR)/util/timer.cpp \
    $(GRANITE_DIR)/vulkan/breadcrumbs.cpp \
    $(GRANITE_DIR)/vulkan/buffer.cpp \
    $(GRANITE_DIR)/vulkan/buffer_pool.cpp \
    $(GRANITE_DIR)/vulkan/command_buffer.cpp \
    $(GRANITE_DIR)/vulkan/command_pool.cpp \
    $(GRANITE_DIR)/vulkan/context.cpp \
    $(GRANITE_DIR)/vulkan/cookie.cpp \
    $(GRANITE_DIR)/vulkan/descriptor_set.cpp \
    $(GRANITE_DIR)/vulkan/device.cpp \
    $(GRANITE_DIR)/vulkan/event_manager.cpp \
    $(GRANITE_DIR)/vulkan/fence.cpp \
    $(GRANITE_DIR)/vulkan/fence_manager.cpp \
    $(GRANITE_DIR)/vulkan/image.cpp \
    $(GRANITE_DIR)/vulkan/indirect_layout.cpp \
    $(GRANITE_DIR)/vulkan/memory_allocator.cpp \
    $(GRANITE_DIR)/vulkan/pipeline_cache.cpp \
    $(GRANITE_DIR)/vulkan/pipeline_event.cpp \
    $(GRANITE_DIR)/vulkan/query_pool.cpp \
    $(GRANITE_DIR)/vulkan/render_pass.cpp \
    $(GRANITE_DIR)/vulkan/renderdoc_capture.cpp \
    $(GRANITE_DIR)/vulkan/rtas.cpp \
    $(GRANITE_DIR)/vulkan/sampler.cpp \
    $(GRANITE_DIR)/vulkan/semaphore.cpp \
    $(GRANITE_DIR)/vulkan/semaphore_manager.cpp \
    $(GRANITE_DIR)/vulkan/shader.cpp \
    $(GRANITE_DIR)/vulkan/texture/texture_format.cpp \
    $(GRANITE_DIR)/vulkan/wsi.cpp \
    $(GRANITE_DIR)/vulkan/wsi_pacer.cpp

PW_INCLUDES := \
    $(LOCAL_PATH)/$(PW_DIR) \
    $(LOCAL_PATH)/$(PW_DIR)/shaders \
    $(LOCAL_PATH)/$(GRANITE_DIR)/video \
    $(LOCAL_PATH)/$(GRANITE_DIR)/vulkan \
    $(LOCAL_PATH)/$(GRANITE_DIR)/util \
    $(LOCAL_PATH)/$(GRANITE_DIR)/math \
    $(LOCAL_PATH)/$(GRANITE_DIR)/application/global \
    $(LOCAL_PATH)/$(GRANITE_DIR)/third_party/volk \
    $(LOCAL_PATH)/$(GRANITE_DIR)/third_party/renderdoc \
    $(LOCAL_PATH)/$(GRANITE_DIR)/third_party/khronos/vulkan-headers/include

LOCAL_C_INCLUDES := $(PW_INCLUDES)
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/$(PW_DIR) $(LOCAL_PATH)/$(GRANITE_DIR)/third_party/khronos/vulkan-headers/include
LOCAL_CFLAGS := -DGRANITE_SHIPPING -DGRANITE_RENDERDOC_CAPTURE -DPYROWAVE_PRECISION=1 \
                -fvisibility=hidden -O2 -Wno-unused-parameter -Wno-unused-variable
LOCAL_CPPFLAGS := -std=c++14 -fvisibility-inlines-hidden -fexceptions -frtti
LOCAL_CPP_FEATURES := exceptions rtti
include $(BUILD_STATIC_LIBRARY)

endif

// Debug-only headless regression probe. Runs the shipped SPIR-V on the device's Vulkan GPU.
// No decoder, surface, swapchain, Activity or renderer-global state is used.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include "shaders_spv.h"

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void checked(VkResult result, const char *operation) {
    require(result == VK_SUCCESS, std::string(operation) + " VkResult=" + std::to_string(result));
}
// Fixtures and readbacks use IEEE binary16, matching the production intermediate format.
uint16_t half(float value) {
    uint32_t bits; std::memcpy(&bits, &value, sizeof(bits));
    uint32_t sign = (bits >> 16) & 0x8000u;
    int exponent = int((bits >> 23) & 255u) - 127 + 15;
    uint32_t mantissa = bits & 0x7fffffu;
    if (exponent <= 0) {
        if (exponent < -10) return uint16_t(sign);
        mantissa = (mantissa | 0x800000u) >> (1 - exponent);
        return uint16_t(sign | ((mantissa + 0x1000u) >> 13));
    }
    if (exponent >= 31) return uint16_t(sign | 0x7c00u);
    uint32_t rounded = mantissa + 0x1000u;
    if (rounded & 0x800000u) { rounded = 0; ++exponent; }
    return uint16_t(sign | (uint32_t(exponent) << 10) | (rounded >> 13));
}
float unhalf(uint16_t value) {
    const float magnitude = (value & 0x7c00u) == 0x7c00u
        ? ((value & 0x03ffu) ? NAN : INFINITY)
        : std::ldexp(float((value & 0x03ffu) + ((value & 0x7c00u) ? 1024 : 0)),
                     ((value >> 10) & 31) ? int((value >> 10) & 31) - 25 : -24);
    return (value & 0x8000u) ? -magnitude : magnitude;
}

#define PROBE_INSTANCE_FUNCTIONS(X) \
    X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceFeatures) X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceFormatProperties) X(CreateDevice) X(GetDeviceProcAddr)
#define PROBE_DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(CreateImage) X(DestroyImage) \
    X(GetImageMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindImageMemory) \
    X(CreateImageView) X(DestroyImageView) X(CreateBuffer) X(DestroyBuffer) \
    X(GetBufferMemoryRequirements) X(BindBufferMemory) X(MapMemory) X(UnmapMemory) \
    X(CreateSampler) X(DestroySampler) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) \
    X(CreatePipelineLayout) X(DestroyPipelineLayout) X(CreateDescriptorPool) X(DestroyDescriptorPool) \
    X(AllocateDescriptorSets) X(UpdateDescriptorSets) X(CreateShaderModule) X(DestroyShaderModule) \
    X(CreateComputePipelines) X(CreateGraphicsPipelines) X(DestroyPipeline) \
    X(CreateRenderPass) X(DestroyRenderPass) X(CreateFramebuffer) X(DestroyFramebuffer) \
    X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(ResetCommandBuffer) \
    X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdCopyBufferToImage) \
    X(CmdCopyImageToBuffer) X(CmdBindPipeline) X(CmdBindDescriptorSets) X(CmdPushConstants) \
    X(CmdDispatch) X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdDraw) X(QueueSubmit) X(QueueWaitIdle)
struct Api {
    void *library = nullptr;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
    PFN_vkCreateInstance CreateInstance = nullptr;
#define DECLARE(name) PFN_vk##name name = nullptr;
    PROBE_INSTANCE_FUNCTIONS(DECLARE)
    PROBE_DEVICE_FUNCTIONS(DECLARE)
#undef DECLARE
    ~Api() { if (library) dlclose(library); }
    void load() {
        library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        require(library != nullptr, "Cannot load libvulkan.so");
        GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
        require(GetInstanceProcAddr != nullptr, "Missing vkGetInstanceProcAddr");
        CreateInstance = reinterpret_cast<PFN_vkCreateInstance>(GetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
        require(CreateInstance != nullptr, "Missing vkCreateInstance");
    }
    void loadInstance(VkInstance instance) {
#define LOAD(name) name = reinterpret_cast<PFN_vk##name>(GetInstanceProcAddr(instance, "vk" #name)); require(name != nullptr, "Missing vk" #name);
        PROBE_INSTANCE_FUNCTIONS(LOAD)
#undef LOAD
    }
    void loadDevice(VkDevice device) {
#define LOAD(name) name = reinterpret_cast<PFN_vk##name>(GetDeviceProcAddr(device, "vk" #name)); require(name != nullptr, "Missing vk" #name);
        PROBE_DEVICE_FUNCTIONS(LOAD)
#undef LOAD
    }
};
struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
};
struct Pipeline {
    VkDescriptorSetLayout descriptors = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkPipelineBindPoint bindPoint = VK_PIPELINE_BIND_POINT_COMPUTE;
    std::vector<uint32_t> bindings;
    uint32_t storageBinding = UINT32_MAX;
};
struct Params {
    int32_t input[2], output[2], flow[2];
    int32_t reset = 1, fullRange = 1, matrix = 1, sixteenBit = 0, mode = 0, padding = 0;
};
static_assert(sizeof(Params) == 48, "Production shader push constants");
struct PresentParams { float sharpness; };

struct Probe {
    static constexpr uint32_t width = 32, height = 24, outWidth = 72, outHeight = 54;
    static constexpr VkDeviceSize stagingBytes = 1024 * 1024;
    Api vk;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkCommandPool commands = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    void *mapped = nullptr;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    std::vector<std::unique_ptr<Image>> images;
    std::vector<std::unique_ptr<Pipeline>> pipelines;
    std::vector<VkShaderModule> modules;
    std::ostringstream report;

    ~Probe() {
        if (device) {
            if (vk.DeviceWaitIdle) vk.DeviceWaitIdle(device);
            if (framebuffer) vk.DestroyFramebuffer(device, framebuffer, nullptr);
            for (auto &p : pipelines) {
                if (p->pipeline) vk.DestroyPipeline(device, p->pipeline, nullptr);
                if (p->layout) vk.DestroyPipelineLayout(device, p->layout, nullptr);
                if (p->descriptors) vk.DestroyDescriptorSetLayout(device, p->descriptors, nullptr);
            }
            for (auto module : modules) vk.DestroyShaderModule(device, module, nullptr);
            if (renderPass) vk.DestroyRenderPass(device, renderPass, nullptr);
            if (descriptorPool) vk.DestroyDescriptorPool(device, descriptorPool, nullptr);
            if (sampler) vk.DestroySampler(device, sampler, nullptr);
            for (auto &image : images) {
                if (image->view) vk.DestroyImageView(device, image->view, nullptr);
                if (image->image) vk.DestroyImage(device, image->image, nullptr);
                if (image->memory) vk.FreeMemory(device, image->memory, nullptr);
            }
            if (mapped) vk.UnmapMemory(device, stagingMemory);
            if (staging) vk.DestroyBuffer(device, staging, nullptr);
            if (stagingMemory) vk.FreeMemory(device, stagingMemory, nullptr);
            if (commands) vk.DestroyCommandPool(device, commands, nullptr);
            if (vk.DestroyDevice) vk.DestroyDevice(device, nullptr);
        }
        if (instance && vk.DestroyInstance) vk.DestroyInstance(instance, nullptr);
    }
    uint32_t memoryType(uint32_t mask, VkMemoryPropertyFlags flags) {
        for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i)
            if ((mask & (1u << i)) && (memoryProperties.memoryTypes[i].propertyFlags & flags) == flags) return i;
        throw std::runtime_error("No suitable Vulkan memory type flags=" + std::to_string(flags));
    }
    void initialize() {
        vk.load();
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "Delos PyroWave GPU regression"; app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; create.pApplicationInfo = &app;
        checked(vk.CreateInstance(&create, nullptr, &instance), "CreateInstance");
        vk.loadInstance(instance);
        uint32_t count = 0; checked(vk.EnumeratePhysicalDevices(instance, &count, nullptr), "EnumeratePhysicalDevices");
        require(count > 0, "No Vulkan physical devices");
        std::vector<VkPhysicalDevice> devices(count);
        checked(vk.EnumeratePhysicalDevices(instance, &count, devices.data()), "EnumeratePhysicalDevices");
        uint32_t family = UINT32_MAX;
        for (auto candidate : devices) {
            VkPhysicalDeviceProperties properties{}; vk.GetPhysicalDeviceProperties(candidate, &properties);
            if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) continue;
            uint32_t families = 0; vk.GetPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
            std::vector<VkQueueFamilyProperties> queues(families);
            vk.GetPhysicalDeviceQueueFamilyProperties(candidate, &families, queues.data());
            for (uint32_t i = 0; i < families; ++i) {
                if (queues[i].queueCount && (queues[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                        (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { physical = candidate; family = i; break; }
            }
            if (physical) break;
        }
        require(physical != VK_NULL_HANDLE, "No hardware graphics+compute Vulkan queue");
        VkPhysicalDeviceProperties properties{}; vk.GetPhysicalDeviceProperties(physical, &properties);
        report << "GPU: " << properties.deviceName << " driver=" << properties.driverVersion
               << " Vulkan=" << VK_VERSION_MAJOR(properties.apiVersion) << '.' << VK_VERSION_MINOR(properties.apiVersion) << '\n';
        VkPhysicalDeviceFeatures supported{}; vk.GetPhysicalDeviceFeatures(physical, &supported);
        require(supported.shaderStorageImageExtendedFormats, "RGBA16F storage images unsupported");
        VkPhysicalDeviceFeatures enabled{}; enabled.shaderStorageImageExtendedFormats = VK_TRUE;
        const float priority = 1;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = family; queueInfo.queueCount = 1; queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        deviceInfo.queueCreateInfoCount = 1; deviceInfo.pQueueCreateInfos = &queueInfo; deviceInfo.pEnabledFeatures = &enabled;
        checked(vk.CreateDevice(physical, &deviceInfo, nullptr, &device), "CreateDevice");
        vk.loadDevice(device); vk.GetDeviceQueue(device, family, 0, &queue);
        vk.GetPhysicalDeviceMemoryProperties(physical, &memoryProperties);
        for (VkFormat format : {VK_FORMAT_R8_UNORM, VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT}) {
            VkFormatProperties formatProperties{}; vk.GetPhysicalDeviceFormatProperties(physical, format, &formatProperties);
            VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if (format == VK_FORMAT_R16G16B16A16_SFLOAT) required |= VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
            require((formatProperties.optimalTilingFeatures & required) == required,
                    "Required format features unavailable, format=" + std::to_string(format));
        }
        VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; pool.queueFamilyIndex = family;
        checked(vk.CreateCommandPool(device, &pool, nullptr, &commands), "CreateCommandPool");
        VkCommandBufferAllocateInfo allocate{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocate.commandPool = commands; allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; allocate.commandBufferCount = 1;
        checked(vk.AllocateCommandBuffers(device, &allocate, &command), "AllocateCommandBuffers");
        VkBufferCreateInfo buffer{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; buffer.size = stagingBytes;
        buffer.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        checked(vk.CreateBuffer(device, &buffer, nullptr, &staging), "CreateBuffer");
        VkMemoryRequirements requirements{}; vk.GetBufferMemoryRequirements(device, staging, &requirements);
        VkMemoryAllocateInfo memory{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; memory.allocationSize = requirements.size;
        memory.memoryTypeIndex = memoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        checked(vk.AllocateMemory(device, &memory, nullptr, &stagingMemory), "AllocateMemory staging");
        checked(vk.BindBufferMemory(device, staging, stagingMemory, 0), "BindBufferMemory");
        checked(vk.MapMemory(device, stagingMemory, 0, stagingBytes, 0, &mapped), "MapMemory");
        VkSamplerCreateInfo sample{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; sample.magFilter = sample.minFilter = VK_FILTER_LINEAR;
        sample.addressModeU = sample.addressModeV = sample.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        checked(vk.CreateSampler(device, &sample, nullptr, &sampler), "CreateSampler");
        VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 48}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 12}};
        VkDescriptorPoolCreateInfo descriptors{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        descriptors.maxSets = 12; descriptors.poolSizeCount = 2; descriptors.pPoolSizes = sizes;
        checked(vk.CreateDescriptorPool(device, &descriptors, nullptr, &descriptorPool), "CreateDescriptorPool");
    }
    void begin() {
        checked(vk.ResetCommandBuffer(command, 0), "ResetCommandBuffer");
        VkCommandBufferBeginInfo info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        checked(vk.BeginCommandBuffer(command, &info), "BeginCommandBuffer");
    }
    void submit() {
        checked(vk.EndCommandBuffer(command), "EndCommandBuffer");
        VkSubmitInfo info{VK_STRUCTURE_TYPE_SUBMIT_INFO}; info.commandBufferCount = 1; info.pCommandBuffers = &command;
        checked(vk.QueueSubmit(queue, 1, &info, VK_NULL_HANDLE), "QueueSubmit");
        checked(vk.QueueWaitIdle(queue), "QueueWaitIdle");
    }
    void barrier() {
        VkMemoryBarrier memory{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        memory.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; memory.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vk.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &memory, 0, nullptr, 0, nullptr);
    }
    Image &image(uint32_t w, uint32_t h, VkFormat format = VK_FORMAT_R16G16B16A16_SFLOAT, bool target = false) {
        images.emplace_back(std::make_unique<Image>()); Image &result = *images.back();
        result.width = w; result.height = h; result.format = format;
        VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; info.imageType = VK_IMAGE_TYPE_2D; info.format = format;
        info.extent = {w, h, 1}; info.mipLevels = info.arrayLayers = 1; info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (format == VK_FORMAT_R16G16B16A16_SFLOAT) info.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
        if (target) info.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        checked(vk.CreateImage(device, &info, nullptr, &result.image), "CreateImage");
        VkMemoryRequirements requirements{}; vk.GetImageMemoryRequirements(device, result.image, &requirements);
        VkMemoryAllocateInfo memory{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; memory.allocationSize = requirements.size;
        memory.memoryTypeIndex = memoryType(requirements.memoryTypeBits, 0);
        checked(vk.AllocateMemory(device, &memory, nullptr, &result.memory), "AllocateMemory image");
        checked(vk.BindImageMemory(device, result.image, result.memory, 0), "BindImageMemory");
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; view.image = result.image;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D; view.format = format; view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        checked(vk.CreateImageView(device, &view, nullptr, &result.view), "CreateImageView");
        begin();
        VkImageMemoryBarrier transition{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        transition.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; transition.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        transition.srcQueueFamilyIndex = transition.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        transition.image = result.image; transition.subresourceRange = view.subresourceRange;
        transition.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vk.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &transition);
        submit(); return result;
    }
    void upload(Image &image, const std::vector<float> &values) {
        const size_t channels = image.format == VK_FORMAT_R16G16B16A16_SFLOAT ? 4 : 1;
        const size_t bytes = values.size() * (image.format == VK_FORMAT_R8_UNORM ? 1 : 2);
        require(values.size() == image.width * image.height * channels && bytes <= stagingBytes, "Fixture size mismatch");
        if (image.format == VK_FORMAT_R8_UNORM) {
            auto *out = static_cast<uint8_t *>(mapped);
            for (size_t i = 0; i < values.size(); ++i) out[i] = uint8_t(std::lround(std::clamp(values[i], 0.f, 1.f) * 255));
        } else {
            auto *out = static_cast<uint16_t *>(mapped);
            for (size_t i = 0; i < values.size(); ++i)
                out[i] = image.format == VK_FORMAT_R16_UNORM ? uint16_t(std::lround(std::clamp(values[i], 0.f, 1.f) * 65535)) : half(values[i]);
        }
        begin(); barrier();
        VkBufferImageCopy region{}; region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; region.imageExtent = {image.width, image.height, 1};
        vk.CmdCopyBufferToImage(command, staging, image.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
        barrier(); submit();
    }
    std::vector<float> readback(Image &image) {
        require(image.format == VK_FORMAT_R16G16B16A16_SFLOAT, "Readback requires RGBA16F");
        begin(); barrier();
        VkBufferImageCopy region{}; region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; region.imageExtent = {image.width, image.height, 1};
        vk.CmdCopyImageToBuffer(command, image.image, VK_IMAGE_LAYOUT_GENERAL, staging, 1, &region);
        VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vk.CmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host, 0, nullptr, 0, nullptr);
        submit();
        std::vector<float> values(image.width * image.height * 4);
        const auto *data = static_cast<const uint16_t *>(mapped);
        for (size_t i = 0; i < values.size(); ++i) values[i] = unhalf(data[i]);
        return values;
    }
    VkShaderModule module(const uint32_t *code, size_t bytes) {
        VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; info.codeSize = bytes; info.pCode = code;
        VkShaderModule result; checked(vk.CreateShaderModule(device, &info, nullptr, &result), "CreateShaderModule");
        modules.push_back(result); return result;
    }
    Pipeline &layout(std::vector<uint32_t> bindings, uint32_t storage, bool graphics = false) {
        pipelines.emplace_back(std::make_unique<Pipeline>()); Pipeline &p = *pipelines.back();
        p.bindings = std::move(bindings); p.storageBinding = storage;
        p.bindPoint = graphics ? VK_PIPELINE_BIND_POINT_GRAPHICS : VK_PIPELINE_BIND_POINT_COMPUTE;
        const VkShaderStageFlags stage = graphics ? VK_SHADER_STAGE_FRAGMENT_BIT : VK_SHADER_STAGE_COMPUTE_BIT;
        std::vector<VkDescriptorSetLayoutBinding> descriptorBindings;
        for (uint32_t binding : p.bindings) descriptorBindings.push_back({binding,
                binding == storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, stage, nullptr});
        VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        info.bindingCount = uint32_t(descriptorBindings.size()); info.pBindings = descriptorBindings.data();
        checked(vk.CreateDescriptorSetLayout(device, &info, nullptr, &p.descriptors), "CreateDescriptorSetLayout");
        VkPushConstantRange range{stage, 0, graphics ? uint32_t(sizeof(PresentParams)) : uint32_t(sizeof(Params))};
        VkPipelineLayoutCreateInfo pipelineLayout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pipelineLayout.setLayoutCount = 1; pipelineLayout.pSetLayouts = &p.descriptors;
        pipelineLayout.pushConstantRangeCount = 1; pipelineLayout.pPushConstantRanges = &range;
        checked(vk.CreatePipelineLayout(device, &pipelineLayout, nullptr, &p.layout), "CreatePipelineLayout");
        VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        allocate.descriptorPool = descriptorPool; allocate.descriptorSetCount = 1; allocate.pSetLayouts = &p.descriptors;
        checked(vk.AllocateDescriptorSets(device, &allocate, &p.set), "AllocateDescriptorSets"); return p;
    }
    Pipeline &compute(const uint32_t *code, size_t bytes, std::vector<uint32_t> bindings, uint32_t storage) {
        Pipeline &p = layout(std::move(bindings), storage);
        VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        info.stage.module = module(code, bytes); info.stage.pName = "main"; info.layout = p.layout;
        checked(vk.CreateComputePipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &p.pipeline), "CreateComputePipelines"); return p;
    }
    void bindImages(Pipeline &p, std::initializer_list<Image *> inputs) {
        require(inputs.size() == p.bindings.size(), "Descriptor image count mismatch");
        std::vector<VkDescriptorImageInfo> descriptors(inputs.size());
        std::vector<VkWriteDescriptorSet> writes(inputs.size());
        size_t i = 0;
        for (auto *image : inputs) {
            const bool storage = p.bindings[i] == p.storageBinding;
            descriptors[i] = {storage ? VK_NULL_HANDLE : sampler, image->view, VK_IMAGE_LAYOUT_GENERAL};
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[i].dstSet = p.set;
            writes[i].dstBinding = p.bindings[i]; writes[i].descriptorCount = 1;
            writes[i].descriptorType = storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[i].pImageInfo = &descriptors[i]; ++i;
        }
        vk.UpdateDescriptorSets(device, uint32_t(writes.size()), writes.data(), 0, nullptr);
    }
    void dispatch(Pipeline &p, const void *params, uint32_t bytes, uint32_t w, uint32_t h) {
        begin(); barrier();
        vk.CmdBindPipeline(command, p.bindPoint, p.pipeline);
        vk.CmdBindDescriptorSets(command, p.bindPoint, p.layout, 0, 1, &p.set, 0, nullptr);
        vk.CmdPushConstants(command, p.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, bytes, params);
        vk.CmdDispatch(command, (w + 7) / 8, (h + 7) / 8, 1); barrier(); submit();
    }
    Pipeline &presentation(Image &target, const uint32_t *fragment, size_t fragmentBytes) {
        // Reuse a compatible render pass/framebuffer for the present and YUV pack fragments.
        if (!renderPass) {
            VkAttachmentDescription color{}; color.format = target.format; color.samples = VK_SAMPLE_COUNT_1_BIT;
            color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
            color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            color.initialLayout = color.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkAttachmentReference reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
            VkSubpassDescription subpass{}; subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = 1; subpass.pColorAttachments = &reference;
            VkSubpassDependency dependencies[2]{};
            dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL; dependencies[0].dstSubpass = 0;
            dependencies[0].srcStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT; dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            dependencies[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            dependencies[1].srcSubpass = 0; dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
            dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dependencies[1].dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT; dependencies[1].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
            VkRenderPassCreateInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO}; pass.attachmentCount = 1; pass.pAttachments = &color;
            pass.subpassCount = 1; pass.pSubpasses = &subpass; pass.dependencyCount = 2; pass.pDependencies = dependencies;
            checked(vk.CreateRenderPass(device, &pass, nullptr, &renderPass), "CreateRenderPass");
            VkFramebufferCreateInfo frame{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; frame.renderPass = renderPass;
            frame.attachmentCount = 1; frame.pAttachments = &target.view; frame.width = target.width; frame.height = target.height; frame.layers = 1;
            checked(vk.CreateFramebuffer(device, &frame, nullptr, &framebuffer), "CreateFramebuffer");
        }
        Pipeline &p = layout({0}, UINT32_MAX, true);
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = module(fullscreen_vert_spv, sizeof(fullscreen_vert_spv)); stages[0].pName = "main";
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = module(fragment, fragmentBytes); stages[1].pName = "main";
        VkPipelineVertexInputStateCreateInfo vertex{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{0, 0, float(target.width), float(target.height), 0, 1}; VkRect2D scissor{{0, 0}, {target.width, target.height}};
        VkPipelineViewportStateCreateInfo viewportInfo{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewportInfo.viewportCount = 1; viewportInfo.pViewports = &viewport; viewportInfo.scissorCount = 1; viewportInfo.pScissors = &scissor;
        VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        raster.polygonMode = VK_POLYGON_MODE_FILL; raster.cullMode = VK_CULL_MODE_NONE; raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; raster.lineWidth = 1;
        VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState attachment{}; attachment.colorWriteMask = 15;
        VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; blend.attachmentCount = 1; blend.pAttachments = &attachment;
        VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        info.stageCount = 2; info.pStages = stages; info.pVertexInputState = &vertex; info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewportInfo; info.pRasterizationState = &raster; info.pMultisampleState = &multisample;
        info.pColorBlendState = &blend; info.layout = p.layout; info.renderPass = renderPass;
        checked(vk.CreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &p.pipeline), "CreateGraphicsPipelines"); return p;
    }
    std::vector<float> present(Pipeline &p, Image &source, Image &target, float sharpness) {
        bindImages(p, {&source}); begin(); barrier();
        VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO}; pass.renderPass = renderPass; pass.framebuffer = framebuffer;
        pass.renderArea.extent = {target.width, target.height};
        vk.CmdBeginRenderPass(command, &pass, VK_SUBPASS_CONTENTS_INLINE);
        vk.CmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline);
        vk.CmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout, 0, 1, &p.set, 0, nullptr);
        PresentParams params{sharpness};
        vk.CmdPushConstants(command, p.layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(params), &params);
        vk.CmdDraw(command, 3, 1, 0, 0); vk.CmdEndRenderPass(command); barrier(); submit(); return readback(target);
    }
    static float difference(const std::vector<float> &a, const std::vector<float> &b) {
        require(a.size() == b.size(), "Readback comparison size mismatch");
        float max = 0; for (size_t i = 0; i < a.size(); ++i) max = std::max(max, std::abs(a[i] - b[i])); return max;
    }
    static void bounded(const std::vector<float> &pixels, float low, float high, const char *label, bool alphaOne = true) {
        for (size_t i = 0; i < pixels.size(); ++i) {
            const float value = pixels[i];
            require(std::isfinite(value), std::string(label) + " nonfinite sample=" + std::to_string(i));
            require(value >= low - .002f && value <= high + .002f, std::string(label) + " out-of-bounds=" + std::to_string(value));
            if (alphaOne && i % 4 == 3) require(std::abs(value - 1) < .001f, std::string(label) + " alpha not opaque");
        }
    }
    static std::vector<float> rgba(uint32_t w, uint32_t h, float value) {
        std::vector<float> pixels(w * h * 4, value);
        for (size_t i = 3; i < pixels.size(); i += 4) pixels[i] = 1;
        return pixels;
    }
    static std::vector<float> fixture(int translation = 0) {
        std::vector<float> pixels(width * height);
        for (uint32_t y = 0; y < height; ++y) for (uint32_t x = 0; x < width; ++x) {
            int q = int(x) - translation;
            pixels[y * width + x] = std::clamp(.43f + .14f * std::sin(q * .71f + y * .23f)
                    + .12f * std::cos(y * .83f - q * .17f) + (q * 3 > int(y) * 4 ? .12f : -.12f), .08f, .92f);
        }
        return pixels;
    }

    void run() {
        initialize();
        Image &y = image(width, height, VK_FORMAT_R8_UNORM);
        Image &cb = image(width / 2, height / 2, VK_FORMAT_R8_UNORM);
        Image &cr = image(width / 2, height / 2, VK_FORMAT_R8_UNORM);
        Image &previous = image(width, height, VK_FORMAT_R8_UNORM);
        Image &history = image(outWidth, outHeight);
        Image &output = image(outWidth, outHeight);
        Image &backward = image(width / 8, height / 8);
        Image &forward = image(width / 8, height / 8);
        Image &activation = image(width, height);
        Image &sceneFlag = image(1, 1);
        upload(cb, std::vector<float>(width * height / 4, 128.f / 255));
        upload(cr, std::vector<float>(width * height / 4, 128.f / 255));
        upload(history, rgba(outWidth, outHeight, .2f));
        upload(backward, rgba(width / 8, height / 8, 0)); upload(forward, rgba(width / 8, height / 8, 0));
        upload(activation, rgba(width, height, 0));
        upload(sceneFlag, rgba(1, 1, 0));
        Pipeline &spatial = compute(sgsr_spatial_comp_spv, sizeof(sgsr_spatial_comp_spv), {0, 1, 2, 6}, 6);
        Pipeline &temporal = compute(sgsr_upscale_comp_spv, sizeof(sgsr_upscale_comp_spv), {0, 1, 2, 3, 4, 5, 6, 7, 8}, 6);
        Pipeline &flow = compute(sgsr_flow_comp_spv, sizeof(sgsr_flow_comp_spv), {0, 1, 2}, 2);
        Pipeline &sceneChange = compute(sgsr_scene_comp_spv, sizeof(sgsr_scene_comp_spv), {0, 1, 2}, 2);
        Pipeline &activate = compute(sgsr_activate_comp_spv, sizeof(sgsr_activate_comp_spv), {0, 1, 2, 3, 4}, 4);
        Params params{{int32_t(width), int32_t(height)}, {int32_t(outWidth), int32_t(outHeight)}, {int32_t(width / 8), int32_t(height / 8)}};
        bindImages(spatial, {&y, &cb, &cr, &output});
        // Constant preservation across all spatial choices, including the explicit Off/bilinear mode.
        for (int mode = 0; mode <= 2; ++mode) {
            float maxError = 0;
            for (int code : {19, 103, 231}) {
                upload(y, std::vector<float>(width * height, code / 255.f)); params.mode = mode;
                dispatch(spatial, &params, sizeof(params), outWidth, outHeight);
                auto pixels = readback(output); bounded(pixels, 0, 1, "Spatial constant");
                for (size_t i = 0; i < pixels.size(); ++i) if (i % 4 != 3) maxError = std::max(maxError, std::abs(pixels[i] - code / 255.f));
            }
            report << "Spatial mode " << mode << " constant maxError=" << maxError << '\n';
            require(maxError < .002f, "Spatial constant preservation");
        }
        auto offSource = fixture(); upload(y, offSource); params.mode = 0;
        dispatch(spatial, &params, sizeof(params), outWidth, outHeight);
        auto off = readback(output); bounded(off, 0, 1, "Off bilinear");
        float bilinearError = 0;
        for (uint32_t oy = 0; oy < outHeight; ++oy) for (uint32_t ox = 0; ox < outWidth; ++ox) {
            float px = (ox + .5f) * width / outWidth - .5f, py = (oy + .5f) * height / outHeight - .5f;
            int x0 = int(std::floor(px)), y0 = int(std::floor(py)); float fx = px - x0, fy = py - y0;
            auto sample = [&](int x, int y) {
                return std::round(offSource[std::clamp(y, 0, int(height) - 1) * width + std::clamp(x, 0, int(width) - 1)] * 255) / 255;
            };
            float top = sample(x0, y0) * (1 - fx) + sample(x0 + 1, y0) * fx;
            float bottom = sample(x0, y0 + 1) * (1 - fx) + sample(x0 + 1, y0 + 1) * fx;
            for (int c = 0; c < 3; ++c)
                bilinearError = std::max(bilinearError, std::abs(off[(oy * outWidth + ox) * 4 + c] - (top * (1 - fy) + bottom * fy)));
        }
        report << "Off bilinear GPU/reference maxError=" << bilinearError << '\n';
        require(bilinearError < .004f, "Off mode is not bilinear passthrough");
        upload(y, fixture()); params.mode = 1; dispatch(spatial, &params, sizeof(params), outWidth, outHeight);
        auto standard = readback(output); bounded(standard, 0, 1, "Standard diagonal");
        params.mode = 2; dispatch(spatial, &params, sizeof(params), outWidth, outHeight);
        auto hq = readback(output); bounded(hq, 0, 1, "HQ diagonal");
        float spatialDelta = difference(standard, hq);
        require(spatialDelta > .0005f && spatialDelta < .35f, "Standard/HQ must differ without unstable edge excursions");
        // Every output must stay inside a generous local 5x5 source neighborhood (not merely finite).
        auto source = fixture();
        for (uint32_t oy = 0; oy < outHeight; ++oy) for (uint32_t ox = 0; ox < outWidth; ++ox) {
            int sx = int(std::floor((ox + .5f) * width / outWidth - .5f));
            int sy = int(std::floor((oy + .5f) * height / outHeight - .5f)); float lo = 1, hi = 0;
            for (int dy = -2; dy <= 2; ++dy) for (int dx = -2; dx <= 2; ++dx) {
                float v = std::round(source[std::clamp(sy + dy, 0, int(height) - 1) * width + std::clamp(sx + dx, 0, int(width) - 1)] * 255) / 255;
                lo = std::min(lo, v); hi = std::max(hi, v);
            }
            for (int c = 0; c < 3; ++c) {
                size_t i = (oy * outWidth + ox) * 4 + c;
                require(standard[i] >= lo - .015f && standard[i] <= hi + .015f && hq[i] >= lo - .015f && hq[i] <= hi + .015f,
                        "Spatial reconstruction escaped local source bounds");
            }
        }
        report << "PASS Standard/HQ textured diagonal local bounds, maxDelta=" << spatialDelta << '\n';

        // Produce history with the real temporal shader, then reconstruct a one-pixel translation.
        bindImages(temporal, {&y, &cb, &cr, &history, &backward, &forward, &output, &activation, &sceneFlag});
        params.mode = 3; params.reset = 1; dispatch(temporal, &params, sizeof(params), outWidth, outHeight);
        upload(history, readback(output)); upload(previous, fixture()); upload(y, fixture(1));
        bindImages(flow, {&y, &previous, &backward}); int32_t flowParams[] = {int32_t(width), int32_t(height), int32_t(width / 8), int32_t(height / 8)};
        dispatch(flow, flowParams, sizeof(flowParams), width / 8, height / 8);
        bindImages(flow, {&previous, &y, &forward}); dispatch(flow, flowParams, sizeof(flowParams), width / 8, height / 8);
        auto motion = readback(backward); bounded(motion, -32, 32, "Backward optical flow", false);
        auto forwardMotion = readback(forward); bounded(forwardMotion, -32, 32, "Forward optical flow", false);
        float maxMotion = 0, maxConfidence = 0;
        for (size_t i = 0; i < motion.size(); i += 4) {
            maxMotion = std::max(maxMotion, std::hypot(motion[i], motion[i + 1])); maxConfidence = std::max(maxConfidence, motion[i + 3]);
            require(motion[i + 3] >= 0 && motion[i + 3] <= 1.002f, "Flow confidence outside [0,1]");
        }
        report << "Measured backward flow maxMotion=" << maxMotion << " confidence=" << maxConfidence << '\n';
        require(maxMotion > .02f, "Translated texture did not produce optical flow");
        bindImages(activate, {&y, &previous, &backward, &forward, &activation});
        params.mode = 4; params.reset = 1; dispatch(activate, &params, sizeof(params), width, height);
        auto resetMask = readback(activation);
        for (float value : resetMask) require(value == 1.f, "Quality activation reset must reject all history");
        params.reset = 0; dispatch(activate, &params, sizeof(params), width, height);
        auto mask = readback(activation); bounded(mask, 0, 1, "Quality activation", false);
        float minActivation = 1, maxActivation = 0;
        for (size_t i = 0; i < mask.size(); i += 4) {
            minActivation = std::min(minActivation, mask[i]); maxActivation = std::max(maxActivation, mask[i]);
        }
        report << "Motion activation range=[" << minActivation << ',' << maxActivation << "]\n";
        params.mode = 3; dispatch(temporal, &params, sizeof(params), outWidth, outHeight); auto regular = readback(output);
        params.mode = 4; dispatch(temporal, &params, sizeof(params), outWidth, outHeight); auto quality = readback(output);
        bounded(regular, 0, 1, "Temporal regular"); bounded(quality, 0, 1, "Temporal Quality");
        float temporalDelta = difference(regular, quality);
        require(temporalDelta > .00001f && temporalDelta < .4f,
                "Regular/Quality motion reconstructions must differ sensibly, maxDelta=" + std::to_string(temporalDelta));
        report << "PASS actual bidirectional flow + Quality activation + temporal motion: maxMotion=" << maxMotion
               << " confidence=" << maxConfidence << " regular/Quality maxDelta=" << temporalDelta << '\n';
        // Whole-frame cut detection: the same picture keeps its history, a different one drops it.
        int32_t sceneParams[] = {int32_t(width), int32_t(height), int32_t(width / 8), int32_t(height / 8)};
        bindImages(sceneChange, {&y, &previous, &sceneFlag});
        upload(y, fixture()); dispatch(sceneChange, sceneParams, sizeof(sceneParams), 8, 8);
        require(readback(sceneFlag)[0] < .5f, "Identical frames were reported as a scene cut");
        upload(y, std::vector<float>(width * height, 23.f / 255)); dispatch(sceneChange, sceneParams, sizeof(sceneParams), 8, 8);
        require(readback(sceneFlag)[0] > .5f, "A different picture was not reported as a scene cut");
        upload(y, fixture(1)); upload(sceneFlag, rgba(1, 1, 0));
        report << "PASS scene cut detection: identical frames keep history, a different picture drops it\n";
        for (int mode : {3, 4}) {
            params.mode = mode; params.reset = 1;
            upload(history, rgba(outWidth, outHeight, .05f)); dispatch(temporal, &params, sizeof(params), outWidth, outHeight); auto resetA = readback(output);
            upload(history, rgba(outWidth, outHeight, .95f)); dispatch(temporal, &params, sizeof(params), outWidth, outHeight); auto resetB = readback(output);
            float resetError = difference(resetA, resetB); require(resetError < .001f, "Reset must discard poisoned history");
            upload(y, std::vector<float>(width * height, 23.f / 255)); params.reset = 0;
            dispatch(activate, &params, sizeof(params), width, height);
            auto sceneMask = readback(activation); bounded(sceneMask, 0, 1, "Scene activation", false);
            float maxSceneActivation = 0;
            for (size_t i = 0; i < sceneMask.size(); i += 4) maxSceneActivation = std::max(maxSceneActivation, sceneMask[i]);
            require(maxSceneActivation > .9f, "Scene cut did not activate fresh reconstruction");
            dispatch(sceneChange, sceneParams, sizeof(sceneParams), 8, 8);
            dispatch(temporal, &params, sizeof(params), outWidth, outHeight); auto scene = readback(output);
            float sceneError = 0; bounded(scene, 0, 1, "Scene transition");
            for (size_t i = 0; i < scene.size(); ++i) if (i % 4 != 3) sceneError = std::max(sceneError, std::abs(scene[i] - 23.f / 255));
            require(sceneError < .003f, "Scene transition retained stale bright history");
            report << "PASS temporal mode " << mode << " reset history invariance=" << resetError << " scene transition error=" << sceneError << '\n';
            upload(y, fixture(1));
        }

        // Verify the production sixteen-bit CSC path, including PQ code values that cannot survive RGBA8.
        Image &y16 = image(width, height, VK_FORMAT_R16_UNORM);
        Image &cb16 = image(width / 2, height / 2, VK_FORMAT_R16_UNORM);
        Image &cr16 = image(width / 2, height / 2, VK_FORMAT_R16_UNORM);
        upload(cb16, std::vector<float>(width * height / 4, .5f)); upload(cr16, std::vector<float>(width * height / 4, .5f));
        bindImages(spatial, {&y16, &cb16, &cr16, &output}); params.sixteenBit = 1; params.matrix = 2; params.mode = 2;
        for (int code : {65, 513, 941}) {
            float value = code / 1023.f; upload(y16, std::vector<float>(width * height, value));
            dispatch(spatial, &params, sizeof(params), outWidth, outHeight); auto hdr = readback(output); float error = 0;
            for (size_t i = 0; i < hdr.size(); ++i) if (i % 4 != 3) error = std::max(error, std::abs(hdr[i] - value));
            require(error < .0006f, "Sixteen-bit BT.2020 constant lost precision");
            report << "PASS 16-bit BT.2020 PQ code=" << code << "/1023 maxError=" << error << '\n';
        }

        Image &presentSource = image(outWidth, outHeight);
        Image &target = image(outWidth, outHeight, VK_FORMAT_R16G16B16A16_SFLOAT, true);
        Pipeline &presentPipeline = presentation(target, sgsr_present_frag_spv, sizeof(sgsr_present_frag_spv));
        Pipeline &packPipeline = presentation(target, sgsr_pack_frag_spv, sizeof(sgsr_pack_frag_spv));
        auto presentationFixture = rgba(outWidth, outHeight, 0);
        for (uint32_t py = 0; py < outHeight; ++py) for (uint32_t px = 0; px < outWidth; ++px) {
            float v = .45f + .18f * std::sin(px * .61f + py * .19f) + .12f * std::cos(py * .53f);
            for (int c = 0; c < 3; ++c) presentationFixture[(py * outWidth + px) * 4 + c] = v;
        }
        upload(presentSource, presentationFixture);
        {
            auto zero = present(presentPipeline, presentSource, target, 0);
            auto sharp = present(presentPipeline, presentSource, target, .85f);
            bounded(zero, 0, 1, "Present zero"); bounded(sharp, 0, 1, "Present sharp");
            float bypassError = 0;
            for (size_t i = 0; i < zero.size(); ++i) bypassError = std::max(bypassError, std::abs(zero[i] - unhalf(half(presentationFixture[i]))));
            require(bypassError < .001f, "Sharpness zero did not bypass spatial processing");
            float sharpDelta = difference(zero, sharp); require(sharpDelta > .0005f && sharpDelta < .25f, "Sharpness control must change actual fragments sensibly");
            auto packZero = present(packPipeline, presentSource, target, 0);
            auto packSharp = present(packPipeline, presentSource, target, .85f);
            bounded(packZero, 0, 1, "Pack zero"); bounded(packSharp, 0, 1, "Pack sharp");
            require(difference(packZero, packSharp) > .0001f, "P010 pack ignored sharpness");
            report << "PASS offscreen present + P010 pack fragments bypassError=" << bypassError << " sharpness maxDelta=" << sharpDelta << '\n';
        }
        // Use different channels: catch accidental luma-only output and 8-bit HDR intermediates.
        auto codes = rgba(outWidth, outHeight, 0);
        const std::array<float, 3> values{65.f / 1023, 513.f / 1023, 941.f / 1023};
        for (size_t i = 0; i < codes.size(); ++i) if (i % 4 != 3) codes[i] = values[i % 4];
        upload(presentSource, codes);
        for (float strength : {0.f, .85f}) {
            auto hdr = present(presentPipeline, presentSource, target, strength); float error = 0;
            for (size_t i = 0; i < hdr.size(); ++i) error = std::max(error, std::abs(hdr[i] - codes[i]));
            require(error < .0003f, "HDR presentation lost ten-bit precision or changed constant colour");
            report << "PASS HDR ten-bit RGB constants sharpness=" << strength << " maxError=" << error << '\n';
            auto packed = present(packPipeline, presentSource, target, strength);
            const float luma = .2627f * unhalf(half(values[0])) + .6780f * unhalf(half(values[1])) + .0593f * unhalf(half(values[2]));
            const std::array<float, 3> expected{
                64.f / 1023 + luma * 876.f / 1023,
                512.f / 1023 + (unhalf(half(values[2])) - luma) / (2 * (1 - .0593f)) * 896.f / 1023,
                512.f / 1023 + (unhalf(half(values[0])) - luma) / (2 * (1 - .2627f)) * 896.f / 1023};
            float packError = 0;
            for (size_t i = 0; i < packed.size(); ++i)
                packError = std::max(packError, std::abs(packed[i] - (i % 4 == 3 ? 1.f : expected[i % 4])));
            require(packError < .001f, "P010 pack lost limited-range BT.2020 code values");
            report << "PASS HDR P010 BT.2020 packing sharpness=" << strength << " maxError=" << packError << '\n';
        }
        report << "PASS all shipped PyroWave reconstruction, motion, activation and presentation shader regressions\n";
    }
};
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_binding_video_PyroWaveGpuInstrumentation_nativeRunProbe(JNIEnv *env, jclass) {
    Probe probe;
    try {
        probe.run();
        return env->NewStringUTF((std::string("PASS\n") + probe.report.str()).c_str());
    } catch (const std::exception &error) {
        return env->NewStringUTF((std::string("FAIL\n") + probe.report.str() + "FAIL: " + error.what() + '\n').c_str());
    } catch (...) {
        return env->NewStringUTF((std::string("FAIL\n") + probe.report.str() + "FAIL: unknown native exception\n").c_str());
    }
}

#include <jni.h>
#include <dlfcn.h>
#include <sched.h>
#include <sys/auxv.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <cstdint>
#include <sstream>
#include <vector>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_motoai_runtime_DiagnosticsActivity_nativeProbe(JNIEnv * env, jobject) {
    std::ostringstream report;
    report << "scope=app UID; capability discovery only, no inference\n";
    report << "pagesize=" << sysconf(_SC_PAGESIZE) << '\n';
    report << "hwcap=" << getauxval(AT_HWCAP) << '\n';
    report << "hwcap2=" << getauxval(AT_HWCAP2) << '\n';
    cpu_set_t cpus;
    CPU_ZERO(&cpus);
    if (sched_getaffinity(0, sizeof(cpus), &cpus) == 0) {
        report << "allowed_cpus=";
        for (int i = 0; i < CPU_SETSIZE; ++i) {
            if (CPU_ISSET(i, &cpus)) report << i << ',';
        }
        report << '\n';
    }

    // OEM OpenCL remains optional. No hard DT_NEEDED dependency on it.
    static void * opencl = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (opencl) {
        using PlatformIds = int32_t (*)(uint32_t, void **, uint32_t *);
        auto getPlatforms = reinterpret_cast<PlatformIds>(dlsym(opencl, "clGetPlatformIDs"));
        uint32_t count = 0;
        const int32_t status = getPlatforms ? getPlatforms(0, nullptr, &count) : -1;
        report << "opencl_platform_status=" << status << '\n';
        report << "opencl_platform_count=" << count << '\n';
    } else {
        const char * error = dlerror();
        report << "opencl_load_error=" << (error ? error : "unknown") << '\n';
    }

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "MotoAI capability probe";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&info, nullptr, &instance);
    report << "vulkan_instance_status=" << result << '\n';
    if (result == VK_SUCCESS) {
        uint32_t count = 0;
        result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
        report << "vulkan_device_count=" << count << '\n';
        if (result == VK_SUCCESS && count <= 32) {
            std::vector<VkPhysicalDevice> devices(count);
            result = vkEnumeratePhysicalDevices(instance, &count, devices.data());
            if (result == VK_SUCCESS) {
                for (uint32_t i = 0; i < count; ++i) {
                    VkPhysicalDeviceProperties properties{};
                    vkGetPhysicalDeviceProperties(devices[i], &properties);
                    report << "vulkan_device=" << properties.deviceName << '\n';
                    report << "vulkan_api=" << properties.apiVersion << '\n';
                    report << "vulkan_driver=" << properties.driverVersion << '\n';
                    uint32_t extensionCount = 0;
                    result = vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extensionCount, nullptr);
                    if (result == VK_SUCCESS && extensionCount <= 4096) {
                        std::vector<VkExtensionProperties> extensions(extensionCount);
                        result = vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extensionCount, extensions.data());
                        if (result == VK_SUCCESS) {
                            for (const auto & extension : extensions) {
                                report << "vulkan_extension=" << extension.extensionName << '\n';
                            }
                        }
                    }
                }
            }
        }
        report << "vulkan_enumerate_status=" << result << '\n';
        vkDestroyInstance(instance, nullptr);
    }
    const auto text = report.str();
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                                    reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
}

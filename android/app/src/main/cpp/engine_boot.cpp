// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
// Android loader adapter. Upstream shader code and numerical guard remain
// unchanged.
#include "engine_boot.hpp"
#include "json.hpp"
#include "volk.h"
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <mutex>
#include <unistd.h>

namespace {
std::mutex state_mutex;
spk::Json state = spk::Json::object();
std::string backend = "gpu", library_dir, state_file;
bool service_process = false;
std::atomic<bool> booting{false};
std::chrono::steady_clock::time_point started;
PFN_vkGetInstanceProcAddr original_gpa = nullptr;
PFN_vkCreateInstance original_instance = nullptr;
PFN_vkEnumeratePhysicalDevices original_enumerate = nullptr;
PFN_vkCreateDevice original_device = nullptr;
PFN_vkCreateComputePipelines original_pipeline = nullptr;
PFN_vkQueueSubmit original_submit = nullptr;
PFN_vkQueueWaitIdle original_wait = nullptr;
VkDevice current_device = VK_NULL_HANDLE;
VkFence pending_fence = VK_NULL_HANDLE;
VkQueue pending_queue = VK_NULL_HANDLE;
void *software_module = nullptr;
void save_locked() {
  if (state_file.empty())
    return;
  std::ofstream out(state_file + ".pending",
                    std::ios::binary | std::ios::trunc);
  out << state.dump();
  out.close();
  if (out)
    std::rename((state_file + ".pending").c_str(), state_file.c_str());
}
void stage(const std::string &value) {
  if (!booting.load())
    return;
  std::lock_guard<std::mutex> lock(state_mutex);
  state.set("stage", spk::Json(value));
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - started)
                .count();
  state.set("elapsed_ms", spk::Json(double(ms)));
  save_locked();
  __android_log_print(ANDROID_LOG_INFO, "SpektraLensEngineBoot", "%s",
                      value.c_str());
}
VKAPI_ATTR VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *a,
                                               const VkAllocationCallbacks *b,
                                               VkInstance *c) {
  stage("vkCreateInstance");
  return original_instance(a, b, c);
}
VKAPI_ATTR VkResult VKAPI_CALL enumerate_devices(VkInstance a, uint32_t *b,
                                                 VkPhysicalDevice *c) {
  stage("vkEnumeratePhysicalDevices");
  return original_enumerate(a, b, c);
}
VKAPI_ATTR VkResult VKAPI_CALL
create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *info,
              const VkAllocationCallbacks *allocator, VkDevice *device) {
  if (booting.load()) {
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physical, &props);
    {
      std::lock_guard<std::mutex> lock(state_mutex);
      state.set("gpu", spk::Json(std::string(props.deviceName)));
      state.set("vendor_id", spk::Json(double(props.vendorID)));
      state.set("device_id", spk::Json(double(props.deviceID)));
      state.set("driver_version", spk::Json(double(props.driverVersion)));
      state.set("vulkan_api", spk::Json(double(props.apiVersion)));
    }
    stage("vkCreateDevice");
  }
  VkResult result = original_device(physical, info, allocator, device);
  if (result == VK_SUCCESS)
    current_device = *device;
  return result;
}
VKAPI_ATTR VkResult VKAPI_CALL
create_pipeline(VkDevice d, VkPipelineCache cache, uint32_t n,
                const VkComputePipelineCreateInfo *infos,
                const VkAllocationCallbacks *allocator, VkPipeline *pipelines) {
  stage("vkCreateComputePipelines:math_probe");
  return original_pipeline(d, cache, n, infos, allocator, pipelines);
}
VKAPI_ATTR VkResult VKAPI_CALL queue_submit(VkQueue queue, uint32_t count,
                                            const VkSubmitInfo *infos,
                                            VkFence fence) {
  if (booting.load())
    stage("vkQueueSubmit:math_probe");
  if (service_process && booting.load() && fence == VK_NULL_HANDLE &&
      current_device) {
    VkFenceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkResult result =
        vkCreateFence(current_device, &info, nullptr, &pending_fence);
    if (result != VK_SUCCESS)
      return result;
    pending_queue = queue;
    result = original_submit(queue, count, infos, pending_fence);
    if (result != VK_SUCCESS) {
      vkDestroyFence(current_device, pending_fence, nullptr);
      pending_fence = VK_NULL_HANDLE;
      pending_queue = VK_NULL_HANDLE;
    }
    return result;
  }
  return original_submit(queue, count, infos, fence);
}
VKAPI_ATTR VkResult VKAPI_CALL queue_wait(VkQueue queue) {
  if (service_process && booting.load() && pending_fence &&
      pending_queue == queue) {
    stage("vkWaitForFences:math_probe");
    VkResult result = vkWaitForFences(current_device, 1, &pending_fence,
                                      VK_TRUE, 8'000'000'000ull);
    if (result == VK_TIMEOUT) {
      mobile::boot_failed("GPU self-test did not finish within 8 seconds");
      // Never free a pending command buffer. This private renderer process is
      // disposable; its owner observes binder death and starts the software
      // renderer in a new process.
      _exit(73);
    }
    vkDestroyFence(current_device, pending_fence, nullptr);
    pending_fence = VK_NULL_HANDLE;
    pending_queue = VK_NULL_HANDLE;
    return result;
  }
  return original_wait(queue);
}
PFN_vkVoidFunction VKAPI_CALL traced_gpa(VkInstance instance,
                                         const char *name) {
  PFN_vkVoidFunction p = original_gpa(instance, name);
  if (!p)
    return p;
#define WRAP(NAME, TYPE, STORAGE, FN)                                          \
  if (std::strcmp(name, NAME) == 0) {                                          \
    STORAGE = reinterpret_cast<TYPE>(p);                                       \
    return reinterpret_cast<PFN_vkVoidFunction>(FN);                           \
  }
  WRAP("vkCreateInstance", PFN_vkCreateInstance, original_instance,
       create_instance)
  WRAP("vkEnumeratePhysicalDevices", PFN_vkEnumeratePhysicalDevices,
       original_enumerate, enumerate_devices)
  WRAP("vkCreateDevice", PFN_vkCreateDevice, original_device, create_device)
  WRAP("vkCreateComputePipelines", PFN_vkCreateComputePipelines,
       original_pipeline, create_pipeline)
  WRAP("vkQueueSubmit", PFN_vkQueueSubmit, original_submit, queue_submit)
  WRAP("vkQueueWaitIdle", PFN_vkQueueWaitIdle, original_wait, queue_wait)
#undef WRAP
  return p;
}
} // namespace
namespace mobile {
void configure_backend(const std::string &mode, const std::string &libraries,
                       const std::string &path) {
  backend = mode;
  library_dir = libraries;
  state_file = path;
  service_process = true;
}
void boot_begin() {
  started = std::chrono::steady_clock::now();
  {
    std::lock_guard<std::mutex> lock(state_mutex);
    state = spk::Json::object();
    state.set("backend", spk::Json(backend));
    state.set("pid", spk::Json(double(getpid())));
    state.set("status", spk::Json(std::string("initializing")));
  }
  booting.store(true);
  stage("spectral_resources");
}
void boot_ready(const std::string &capabilities) {
  stage("ready");
  std::lock_guard<std::mutex> lock(state_mutex);
  state.set("status", spk::Json(std::string("ready")));
  spk::Json cap;
  std::string err;
  if (spk::Json::parse(capabilities, cap, err))
    state.set("capabilities", cap);
  save_locked();
  booting.store(false);
}
void boot_failed(const std::string &error) {
  std::lock_guard<std::mutex> lock(state_mutex);
  state.set("status", spk::Json(std::string("failed")));
  state.set("error", spk::Json(error));
  save_locked();
  booting.store(false);
}
std::string boot_snapshot() {
  std::lock_guard<std::mutex> lock(state_mutex);
  return state.dump();
}
} // namespace mobile
extern "C" VkResult spk_mobile_volk_initialize() {
  stage("vulkan_loader");
  if (backend == "cpu") {
    if (!software_module)
      software_module = dlopen((library_dir + "/libvk_swiftshader.so").c_str(),
                               RTLD_NOW | RTLD_LOCAL);
    if (!software_module)
      software_module = dlopen("libvk_swiftshader.so", RTLD_NOW | RTLD_LOCAL);
    if (!software_module) {
      const char *error = dlerror();
      mobile::boot_failed(error ? error
                                : "Cannot load bundled software Vulkan");
      return VK_ERROR_INITIALIZATION_FAILED;
    }
    original_gpa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(software_module, "vkGetInstanceProcAddr"));
    if (!original_gpa)
      return VK_ERROR_INITIALIZATION_FAILED;
  } else {
    VkResult result = volkInitialize();
    if (result != VK_SUCCESS)
      return result;
    original_gpa = vkGetInstanceProcAddr;
  }
  volkInitializeCustom(traced_gpa);
  stage("vulkan_loader_ready");
  return VK_SUCCESS;
}

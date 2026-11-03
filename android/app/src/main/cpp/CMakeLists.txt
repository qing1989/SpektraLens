cmake_minimum_required(VERSION 3.22.1)
project(spektra_android LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
set(ENGINE "${ROOT}/.tooling/android-engine-source/engine")
set(SPEKTRALAB_LIBRAW_SOURCE "${ROOT}/third_party/LibRaw-0.22.2" CACHE PATH "Pinned LibRaw source")
include("${ENGINE}/cmake/LibRaw.cmake")
# The reproducible Android overlay preserves the upstream material data/model.
file(GLOB CORE "${ENGINE}/src/core/*.cpp")
add_library(spektra SHARED
    bridge.cpp engine_boot.cpp edit_pipeline.cpp raw_adapter.cpp output_spaces.cpp ${CORE}
    "${ENGINE}/src/io/raw_decoder.cpp"
    "${ENGINE}/src/io/raw_headroom.cpp"
    "${ENGINE}/host/output.cpp"
    "${ENGINE}/host/platform.cpp"
    "${ENGINE}/host/stb_impl.cpp"
    "${ENGINE}/src/pipeline/blur.cpp"
    "${ENGINE}/src/pipeline/engine.cpp"
    "${ENGINE}/src/pipeline/pipeline.cpp"
    "${ENGINE}/src/pipeline/scene_latitude.cpp"
    "${ENGINE}/src/pipeline/windows_unported.cpp"
    "${ENGINE}/src/gpu/vulkan_gpu.cpp"
    "${ENGINE}/third_party/volk/volk.c")
target_include_directories(spektra PRIVATE
    "${ENGINE}/include" "${ENGINE}/src" "${ENGINE}/src/core"
    "${ENGINE}/third_party/volk"
    "${ENGINE}/third_party/stb" "${ENGINE}/host"
    "${ROOT}/third_party/Vulkan-Headers/include")
# APKs are also delivered as debug builds. Keep debugging and GPU probes, but
# compile pixel loops and codecs with normal optimization in every build type.
# Preserve strict floating-point operations used by the spectral/parity checks.
target_compile_options(spektra PRIVATE -O2 -fno-fast-math -ffp-contract=off)
target_link_libraries(spektra PRIVATE spk_libraw android jnigraphics log dl z)
target_link_options(spektra PRIVATE "-Wl,-z,max-page-size=16384")
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_sources(spektra PRIVATE gpu_checks.cpp)
endif()

# Redirect only this loader call through the Android startup adapter; preserve upstream sources.
set_source_files_properties("${ENGINE}/src/gpu/vulkan_gpu.cpp" PROPERTIES COMPILE_DEFINITIONS "volkInitialize=spk_mobile_volk_initialize")

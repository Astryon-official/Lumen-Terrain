cmake_minimum_required(VERSION 3.20)

project(LumenTerrainEngine VERSION 2.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

#
# Pin the OpenCL API version. The deprecated clCreateCommandQueue is
# kept as a runtime fallback for OpenCL 1.2-only ICDs; this define
# acknowledges that deliberately.
#
add_compile_definitions(
    CL_TARGET_OPENCL_VERSION=300
    CL_USE_DEPRECATED_OPENCL_2_0_APIS
)

#
# OpenCL SDK resolution (LTE 2.0.0):
#   1. System OpenCL SDK if installed.
#   2. Vendored Khronos headers + import library generated from the
#      installed OpenCL.dll loader (tools/make_opencl_def.ps1).
#
set(LTE_OPENCL_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL-Headers")
set(LTE_OPENCL_LIB "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL.lib")

if(EXISTS "${LTE_OPENCL_LIB}")
    set(OpenCL_FOUND TRUE)
    message(STATUS "LTE: using vendored OpenCL headers + import library")
else()
    find_package(OpenCL REQUIRED)
endif()

find_package(Java REQUIRED)
find_package(JNI REQUIRED)


include_directories(
    ${JNI_INCLUDE_DIRS}
    ${LTE_OPENCL_INCLUDE}
)

link_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

add_library(LumenTerrainEngine SHARED

    src/LTE.cpp
    src/Logger.cpp
    src/HardwareManager.cpp
    src/CpuCompute.cpp
    src/LTENative.cpp
    src/DeviceRuntime.cpp
    src/OpenCLManager.cpp
    src/OpenCLCompute.cpp
)

target_include_directories(
    LumenTerrainEngine
    PUBLIC
    include
)

#
# Export every public symbol from the DLL so LTE_Test and the JVM
# can resolve them without per-symbol dllexport annotations.
# JNI functions are already extern "C" and exported regardless.
#
set_target_properties(LumenTerrainEngine PROPERTIES
    WINDOWS_EXPORT_ALL_SYMBOLS ON
)

if(TARGET OpenCL::OpenCL)
    target_link_libraries(LumenTerrainEngine OpenCL::OpenCL)
else()
    target_link_libraries(LumenTerrainEngine "${LTE_OPENCL_LIB}")
endif()

if(MSVC)
    target_compile_options(LumenTerrainEngine PRIVATE /W3)
else()
    target_compile_options(LumenTerrainEngine PRIVATE -Wall -Wextra)
endif()


#
# Native equivalence test:
# verifies the CPU reference implementation and the OpenCL kernel
# produce identical terrain output for identical input, and runs both
# benchmarks when a device is available.
#
add_executable(LTE_Test
    src/test.cpp
)

target_link_libraries(
    LTE_Test
    LumenTerrainEngine
)

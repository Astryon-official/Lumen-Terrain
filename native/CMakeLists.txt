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
# OpenCL SDK resolution (LTE 2.x):
#   1. Pre-built import library at third_party/OpenCL.lib (local dev).
#   2. MSVC (incl. ARM64 cross-builds): generate an architecture-correct
#      import library from the vendored opencl.def using the toolset's
#      own lib.exe. Extern "C" symbols are undecorated on X64 and ARM64,
#      so one def file serves every architecture.
#   3. Otherwise: system OpenCL SDK (Linux/macOS CI installs headers).
#
set(LTE_OPENCL_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL-Headers")
set(LTE_OPENCL_LIB     "${CMAKE_CURRENT_SOURCE_DIR}/third_party/OpenCL.lib")
set(LTE_OPENCL_DEF     "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencl.def")
set(LTE_OPENCL_GENLIB  "${CMAKE_CURRENT_BINARY_DIR}/OpenCL.import.lib")

if(EXISTS "${LTE_OPENCL_LIB}")
    message(STATUS "LTE: using prebuilt OpenCL import library")
    set(LTE_OPENCL_LINK "${LTE_OPENCL_LIB}")
elseif(MSVC AND EXISTS "${LTE_OPENCL_DEF}")
    message(STATUS "LTE: generating OpenCL import library from opencl.def")
    if(CMAKE_VS_PLATFORM_NAME MATCHES "[Aa][Rr][Mm]")
        set(LTE_OPENCL_MACHINE "/machine:ARM64")
    else()
        set(LTE_OPENCL_MACHINE "/machine:X64")
    endif()
    get_filename_component(LTE_LIB_TOOL_HINT "${CMAKE_CXX_COMPILER}" DIRECTORY)
    find_program(LTE_LIB_TOOL NAMES lib.exe HINTS "${LTE_LIB_TOOL_HINT}")
    if(NOT LTE_LIB_TOOL)
        message(FATAL_ERROR "LTE: lib.exe not found next to ${CMAKE_CXX_COMPILER}")
    endif()
    add_custom_command(
        OUTPUT "${LTE_OPENCL_GENLIB}"
        COMMAND "${LTE_LIB_TOOL}" /nologo ${LTE_OPENCL_MACHINE}
                /def:${LTE_OPENCL_DEF}
                /out:${LTE_OPENCL_GENLIB}
        DEPENDS "${LTE_OPENCL_DEF}"
        COMMENT "LTE: lib.exe ${LTE_OPENCL_MACHINE} -> OpenCL import library"
        VERBATIM)
    add_custom_target(lte_opencl_import_lib DEPENDS "${LTE_OPENCL_GENLIB}")
    set(LTE_OPENCL_LINK "${LTE_OPENCL_GENLIB}")
else()
    find_package(OpenCL REQUIRED)
    set(LTE_OPENCL_LINK OpenCL::OpenCL)
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

if(TARGET lte_opencl_import_lib)
    add_dependencies(LumenTerrainEngine lte_opencl_import_lib)
endif()
target_link_libraries(LumenTerrainEngine PRIVATE "${LTE_OPENCL_LINK}")

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

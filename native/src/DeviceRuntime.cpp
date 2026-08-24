#include "DeviceRuntime.h"
#include "TerrainAlgorithm.h"
#include "Logger.h"

#include <CL/cl.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

namespace LTE
{

namespace
{

/*
 * Per-device state. One DeviceState is owned per enumerated device;
 * a failure anywhere in its pipeline only poisons this struct.
 */
struct DeviceState
{
    OpenCLDeviceInfo info;

    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_program program = nullptr;
    cl_kernel kernel = nullptr;
    cl_mem heightBuffer = nullptr;   // int[256] read-only
    cl_mem outputBuffer = nullptr;   // float[256] write-only

    bool ready = false;       // resources created successfully
    bool enabled = true;      // config escape hatch
    bool healthy = true;      // runtime health (failures clear it)
    long long cooldownUntilMs = 0;  // steady_clock ms
};

std::vector<DeviceState> g_devices;
bool g_initialized = false;

constexpr long long kCooldownMs = 5000;      // unhealthy retry delay
constexpr int kWarmupChunks = 20;
constexpr int kBenchmarkChunks = 300;

/*
 * Benchmark sink so the optimizer cannot elide measured work.
 */
double g_sink = 0.0;

long long NowMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

/*
 * OpenCL 2.0 command-queue creation is resolved at runtime rather than
 * linked directly: macOS ships an OpenCL 1.2-only system framework that
 * never exports clCreateCommandQueueWithProperties, so a static reference
 * would break the link there. Querying the entry point through the
 * mandatory 1.2 extension-address API keeps one binary portable across
 * 1.2-only platforms (Apple), ICD loaders (Windows/Linux), and 2.0+
 * runtimes alike.
 */
using PFN_clCreateCommandQueueWithProperties = cl_command_queue(CL_API_CALL *)(
    cl_context,
    cl_device_id,
    const cl_queue_properties*,
    cl_int*);

PFN_clCreateCommandQueueWithProperties GetQueue20EntryPoint(
    cl_platform_id platform)
{
    if (auto* fn =
            reinterpret_cast<PFN_clCreateCommandQueueWithProperties>(
                clGetExtensionFunctionAddressForPlatform(
                    platform, "clCreateCommandQueueWithProperties")))
    {
        return fn;
    }

    // Legacy pre-1.2 lookup as a secondary chance (some old ICDs).
    return reinterpret_cast<PFN_clCreateCommandQueueWithProperties>(
        clGetExtensionFunctionAddress("clCreateCommandQueueWithProperties"));
}

std::string GetPlatformString(cl_platform_id p, cl_platform_info which)
{
    size_t size = 0;

    if (clGetPlatformInfo(p, which, 0, nullptr, &size) != CL_SUCCESS || size == 0)
    {
        return {};
    }

    std::string value(size, '\0');

    if (clGetPlatformInfo(p, which, size, value.data(), nullptr) != CL_SUCCESS)
    {
        return {};
    }

    while (!value.empty() && value.back() == '\0')
    {
        value.pop_back();
    }

    return value;
}

std::string GetDeviceString(cl_device_id d, cl_device_info which)
{
    size_t size = 0;

    if (clGetDeviceInfo(d, which, 0, nullptr, &size) != CL_SUCCESS || size == 0)
    {
        return {};
    }

    std::string value(size, '\0');

    if (clGetDeviceInfo(d, which, size, value.data(), nullptr) != CL_SUCCESS)
    {
        return {};
    }

    while (!value.empty() && value.back() == '\0')
    {
        value.pop_back();
    }

    return value;
}

void FillDeviceInfo(
    cl_platform_id plat,
    cl_device_id dev,
    const std::string& platName,
    OpenCLDeviceInfo& out
)
{
    out.platform = plat;
    out.device = dev;
    out.platformName = platName;

    out.name = GetDeviceString(dev, CL_DEVICE_NAME);
    out.vendor = GetDeviceString(dev, CL_DEVICE_VENDOR);
    out.driverVersion = GetDeviceString(dev, CL_DRIVER_VERSION);
    out.openclVersion = GetDeviceString(dev, CL_DEVICE_VERSION);

    size_t bytes = 0;

    out.globalMemory = 0;

    if (clGetDeviceInfo(dev, CL_DEVICE_GLOBAL_MEM_SIZE,
            sizeof(out.globalMemory), &out.globalMemory, &bytes) != CL_SUCCESS)
    {
        out.globalMemory = 0;
    }

    out.localMemory = 0;

    if (clGetDeviceInfo(dev, CL_DEVICE_LOCAL_MEM_SIZE,
            sizeof(out.localMemory), &out.localMemory, &bytes) != CL_SUCCESS)
    {
        out.localMemory = 0;
    }

    out.computeUnits = 0;

    cl_uint cus = 0;

    if (clGetDeviceInfo(dev, CL_DEVICE_MAX_COMPUTE_UNITS,
            sizeof(cus), &cus, &bytes) == CL_SUCCESS)
    {
        out.computeUnits = static_cast<unsigned int>(cus);
    }

    /*
     * Heuristic only - used for logging/priority, never correctness.
     * Intel/AMD iGPUs report "Graphics"/"Radeon" style names.
     */
    const std::string lower = out.name;
    out.integrated =
        lower.find("Intel") != std::string::npos
        && lower.find("Iris") == std::string::npos
        && (
            lower.find("Graphics") != std::string::npos
            || lower.find("UHD") != std::string::npos);
}

/*
 * Create program/kernel/buffers for one device.
 * On any failure the pipeline is released and the failure point is
 * logged with its OpenCL error code.
 */
bool BuildResources(DeviceState& dev, int deviceIndex)
{
    auto fail = [&](const char* what, cl_int code) -> bool
    {
        Log(("OpenCL[" + std::to_string(deviceIndex) + "]: " + what
             + " failed (" + std::to_string(static_cast<long long>(code)) + ")").c_str());
        return false;
    };

    if (!dev.context || !dev.info.device)
    {
        return fail("context/device missing", CL_INVALID_VALUE);
    }

    cl_int result = CL_SUCCESS;

    const char* source = TerrainAlgorithm::KERNEL_SOURCE;
    const size_t sourceLength = std::strlen(source);

    dev.program =
        clCreateProgramWithSource(
            dev.context, 1, &source, &sourceLength, &result);

    if (result != CL_SUCCESS || !dev.program)
    {
        return fail("program creation", result);
    }

    result =
        clBuildProgram(dev.program, 1, &dev.info.device, nullptr, nullptr, nullptr);

    if (result != CL_SUCCESS)
    {
        size_t logSize = 0;

        clGetProgramBuildInfo(
            dev.program, dev.info.device,
            CL_PROGRAM_BUILD_LOG, 0, nullptr, &logSize);

        if (logSize > 1 && logSize < 8192)
        {
            std::string logText(logSize, '\0');

            clGetProgramBuildInfo(
                dev.program, dev.info.device,
                CL_PROGRAM_BUILD_LOG, logSize, logText.data(), nullptr);

            Log(logText.c_str());
        }

        clReleaseProgram(dev.program);
        dev.program = nullptr;
        return fail("kernel compilation", result);
    }

    dev.kernel =
        clCreateKernel(dev.program, "generateTerrain", &result);

    if (result != CL_SUCCESS || !dev.kernel)
    {
        return fail("kernel creation", result);
    }

    dev.heightBuffer =
        clCreateBuffer(
            dev.context,
            CL_MEM_READ_ONLY,
            sizeof(int) * TerrainAlgorithm::COLUMN_COUNT,
            nullptr,
            &result);

    if (result != CL_SUCCESS || !dev.heightBuffer)
    {
        return fail("height buffer allocation", result);
    }

    dev.outputBuffer =
        clCreateBuffer(
            dev.context,
            CL_MEM_WRITE_ONLY,
            sizeof(float) * TerrainAlgorithm::COLUMN_COUNT,
            nullptr,
            &result);

    if (result != CL_SUCCESS || !dev.outputBuffer)
    {
        return fail("output buffer allocation", result);
    }

    result = CL_SUCCESS;
    result |= clSetKernelArg(dev.kernel, 0, sizeof(cl_mem), &dev.heightBuffer);
    result |= clSetKernelArg(dev.kernel, 1, sizeof(cl_mem), &dev.outputBuffer);

    if (result != CL_SUCCESS)
    {
        return fail("kernel argument setup", result);
    }

    Log(("OpenCL[" + std::to_string(deviceIndex) + "]: pipeline ready ("
         + dev.info.name + ")").c_str());

    return true;
}

/*
 * Release the per-device compute pipeline (program/kernel/buffers).
 *
 * Called when a dispatch/build fails so the next attempt starts from a
 * clean slate. Deliberately does NOT touch context/queue: those are
 * created once in Initialize() and are cheap to reuse, expensive to
 * recreate - and losing them would make every future retry fail.
 */
void ReleasePipeline(DeviceState& dev)
{
    if (dev.outputBuffer) { clReleaseMemObject(dev.outputBuffer); dev.outputBuffer = nullptr; }
    if (dev.heightBuffer) { clReleaseMemObject(dev.heightBuffer); dev.heightBuffer = nullptr; }
    if (dev.kernel) { clReleaseKernel(dev.kernel); dev.kernel = nullptr; }
    if (dev.program) { clReleaseProgram(dev.program); dev.program = nullptr; }
    dev.ready = false;
}

/* Full teardown - Shutdown() only. */
void ReleaseResources(DeviceState& dev)
{
    ReleasePipeline(dev);
    if (dev.queue) { clFinish(dev.queue); clReleaseCommandQueue(dev.queue); dev.queue = nullptr; }
    if (dev.context) { clReleaseContext(dev.context); dev.context = nullptr; }
}

} // namespace


int DeviceRuntime::Initialize()
{
    if (g_initialized)
    {
        return static_cast<int>(g_devices.size());
    }

    g_devices.clear();


    cl_uint platformCount = 0;

    cl_int result = clGetPlatformIDs(0, nullptr, &platformCount);

    if (result != CL_SUCCESS || platformCount == 0)
    {
        Log("OpenCL: no platforms available");
        g_initialized = true;
        return 0;
    }


    std::vector<cl_platform_id> platforms(platformCount);

    result = clGetPlatformIDs(platformCount, platforms.data(), &platformCount);

    if (result != CL_SUCCESS)
    {
        Log("OpenCL: platform enumeration failed");
        g_initialized = true;
        return 0;
    }


    for (cl_uint p = 0; p < platformCount; ++p)
    {
        const std::string platName =
            GetPlatformString(platforms[p], CL_PLATFORM_NAME);

        cl_uint deviceCount = 0;

        result =
            clGetDeviceIDs(platforms[p], CL_DEVICE_TYPE_ALL, 0, nullptr, &deviceCount);

        if (result != CL_SUCCESS || deviceCount == 0)
        {
            continue;
        }


        std::vector<cl_device_id> devices(deviceCount);

        result =
            clGetDeviceIDs(platforms[p], CL_DEVICE_TYPE_ALL, deviceCount, devices.data(), &deviceCount);

        if (result != CL_SUCCESS)
        {
            continue;
        }


        for (cl_uint d = 0; d < deviceCount; ++d)
        {
            cl_device_type type = 0;
            size_t bytes = 0;

            if (clGetDeviceInfo(devices[d], CL_DEVICE_TYPE,
                    sizeof(type), &type, &bytes) == CL_SUCCESS
                && (type & CL_DEVICE_TYPE_CPU) != 0)
            {
                continue;   // explicit CPU devices are handled by CpuCompute
            }

            OpenCLDeviceInfo info;
            FillDeviceInfo(platforms[p], devices[d], platName, info);

            if (info.name.empty())
            {
                continue;   // unresponsive device entry
            }

            cl_bool available = CL_FALSE;

            if (clGetDeviceInfo(devices[d], CL_DEVICE_AVAILABLE,
                    sizeof(available), &available, &bytes) == CL_SUCCESS
                && available == CL_FALSE)
            {
                continue;
            }

            DeviceState dev;
            dev.info = info;

            cl_int ctxResult = CL_SUCCESS;

            dev.context =
                clCreateContext(nullptr, 1, &devices[d], nullptr, nullptr, &ctxResult);

            if (ctxResult != CL_SUCCESS || !dev.context)
            {
                Log("OpenCL: context creation failed for a device");
                continue;   // this device unusable; keep enumerating others
            }

            if (auto* queue20 = GetQueue20EntryPoint(platforms[p]))
            {
                const cl_queue_properties queueProps[] =
                {
                    CL_QUEUE_PROPERTIES, 0,
                    0
                };

                dev.queue =
                    queue20(dev.context, devices[d], queueProps, &ctxResult);

                if (ctxResult != CL_SUCCESS || !dev.queue)
                {
                    dev.queue = nullptr;
                }
            }

            if (!dev.queue)
            {
                dev.queue =
                    clCreateCommandQueue(dev.context, devices[d], 0, &ctxResult);

                if (ctxResult != CL_SUCCESS || !dev.queue)
                {
                    Log("OpenCL: command queue creation failed for a device");
                    clReleaseContext(dev.context);
                    dev.context = nullptr;
                    continue;
                }
            }

            g_devices.push_back(dev);

            Log(("OpenCL device " + std::to_string(static_cast<int>(g_devices.size()) - 1)
                 + ": " + info.name).c_str());
        }
    }


    g_initialized = true;

    Log(("OpenCL: " + std::to_string(g_devices.size())
         + " usable GPU device(s)").c_str());

    return static_cast<int>(g_devices.size());
}


void DeviceRuntime::Shutdown()
{
    for (auto& dev : g_devices)
    {
        ReleaseResources(dev);
    }

    g_devices.clear();
    g_initialized = false;
}


bool DeviceRuntime::IsInitialized()
{
    return g_initialized && !g_devices.empty();
}


int DeviceRuntime::DeviceCount()
{
    return static_cast<int>(g_devices.size());
}


const OpenCLDeviceInfo& DeviceRuntime::GetDeviceInfo(int index)
{
    static const OpenCLDeviceInfo empty{};

    if (index < 0 || index >= static_cast<int>(g_devices.size()))
    {
        return empty;
    }

    return g_devices[static_cast<size_t>(index)].info;
}


/*
 * Health semantics (scheduler contract):
 *   - disabled devices are never healthy
 *   - failed devices self-heal: ProcessTerrain retries after the
 *     cooldown expires (half-open), so this only gates scheduling
 */
bool DeviceRuntime::IsHealthy(int index)
{
    if (index < 0 || index >= static_cast<int>(g_devices.size()))
    {
        return false;
    }

    const DeviceState& dev = g_devices[static_cast<size_t>(index)];

    if (!dev.enabled || !dev.ready || !dev.healthy)
    {
        return false;
    }

    return NowMs() >= dev.cooldownUntilMs;
}


void DeviceRuntime::SetEnabled(int index, bool enabled)
{
    if (index >= 0 && index < static_cast<int>(g_devices.size()))
    {
        g_devices[static_cast<size_t>(index)].enabled = enabled;
    }
}


bool DeviceRuntime::IsEnabled(int index)
{
    if (index < 0 || index >= static_cast<int>(g_devices.size()))
    {
        return false;
    }

    return g_devices[static_cast<size_t>(index)].enabled;
}


bool DeviceRuntime::ProcessTerrain(
    int deviceIndex,
    int x,
    int z,
    const int* heightmap,
    double* output)
{
    if (deviceIndex < 0 || deviceIndex >= static_cast<int>(g_devices.size()))
    {
        return false;
    }

    DeviceState& dev = g_devices[static_cast<size_t>(deviceIndex)];

    if (!heightmap || !output || !dev.enabled)
    {
        return false;
    }


    /*
     * Lazy (re)build with half-open cooldown: an unhealthy device is
     * retried at most every kCooldownMs, so a broken driver does not
     * burn cycles on every chunk.
     */
    if (!dev.ready || !dev.healthy)
    {
        if (NowMs() < dev.cooldownUntilMs)
        {
            return false;
        }

        ReleasePipeline(dev);
        dev.healthy = true;

        if (!BuildResources(dev, deviceIndex))
        {
            ReleasePipeline(dev);
            dev.healthy = false;
            dev.cooldownUntilMs = NowMs() + kCooldownMs;
            return false;
        }

        dev.ready = true;
    }

    cl_int result =
        clEnqueueWriteBuffer(
            dev.queue,
            dev.heightBuffer,
            CL_FALSE,
            0,
            sizeof(int) * TerrainAlgorithm::COLUMN_COUNT,
            heightmap,
            0, nullptr, nullptr);

    if (result != CL_SUCCESS)
    {
        Log(("OpenCL[" + std::to_string(deviceIndex) + "]: buffer upload failed ("
             + std::to_string(static_cast<long long>(result)) + ")").c_str());
        dev.healthy = false;
        dev.cooldownUntilMs = NowMs() + kCooldownMs;
        ReleasePipeline(dev);   // keep context/queue; rebuild pipeline only
        return false;           // pipeline is invalid - do NOT touch kernel
    }

    result = CL_SUCCESS;
    result |= clSetKernelArg(dev.kernel, 2, sizeof(int), &x);
    result |= clSetKernelArg(dev.kernel, 3, sizeof(int), &z);

    if (result != CL_SUCCESS)
    {
        Log(("OpenCL[" + std::to_string(deviceIndex) + "]: arg setup failed").c_str());
        dev.healthy = false;
        dev.cooldownUntilMs = NowMs() + kCooldownMs;
        ReleasePipeline(dev);
        return false;
    }

    size_t globalSize = TerrainAlgorithm::COLUMN_COUNT;

    result =
        clEnqueueNDRangeKernel(
            dev.queue, dev.kernel, 1, nullptr,
            &globalSize, nullptr, 0, nullptr, nullptr);

    if (result != CL_SUCCESS)
    {
        Log(("OpenCL[" + std::to_string(deviceIndex) + "]: kernel dispatch failed ("
             + std::to_string(static_cast<long long>(result)) + ")").c_str());
        dev.healthy = false;
        dev.cooldownUntilMs = NowMs() + kCooldownMs;
        ReleasePipeline(dev);
        return false;
    }

    /*
     * Blocking read doubles as the sync point. Kernel writes fp32;
     * the host converts to the double API contract.
     */
    float floatOutput[TerrainAlgorithm::COLUMN_COUNT];

    result =
        clEnqueueReadBuffer(
            dev.queue,
            dev.outputBuffer,
            CL_TRUE,
            0,
            sizeof(float) * TerrainAlgorithm::COLUMN_COUNT,
            floatOutput,
            0, nullptr, nullptr);

    if (result != CL_SUCCESS)
    {
        Log(("OpenCL[" + std::to_string(deviceIndex) + "]: result download failed ("
             + std::to_string(static_cast<long long>(result)) + ")").c_str());
        dev.healthy = false;
        dev.cooldownUntilMs = NowMs() + kCooldownMs;
        ReleasePipeline(dev);
        return false;
    }

    for (int i = 0; i < TerrainAlgorithm::COLUMN_COUNT; ++i)
    {
        output[i] = static_cast<double>(floatOutput[i]);
    }

    return true;
}


long DeviceRuntime::RunBenchmark(int deviceIndex)
{
    if (deviceIndex < 0 || deviceIndex >= static_cast<int>(g_devices.size()))
    {
        return 0;
    }

    DeviceState& dev = g_devices[static_cast<size_t>(deviceIndex)];

    if (!dev.enabled)
    {
        return 0;
    }

    if (!dev.ready || !dev.healthy)
    {
        ReleasePipeline(dev);

        if (!BuildResources(dev, deviceIndex))
        {
            ReleasePipeline(dev);
            dev.healthy = false;
            dev.cooldownUntilMs = NowMs() + kCooldownMs;
            return 0;
        }

        dev.ready = true;
        dev.healthy = true;
    }

    int hostHeightmap[TerrainAlgorithm::COLUMN_COUNT];

    unsigned int seed = 12345u;

    for (int i = 0; i < TerrainAlgorithm::COLUMN_COUNT; ++i)
    {
        seed = seed * 1664525u + 1013904223u;
        hostHeightmap[i] = 32 + static_cast<int>(seed % 64u);
    }

    auto runChunk = [&](int cx, int cz) -> bool
    {
        cl_int r =
            clEnqueueWriteBuffer(
                dev.queue, dev.heightBuffer, CL_TRUE, 0,
                sizeof(int) * TerrainAlgorithm::COLUMN_COUNT,
                hostHeightmap, 0, nullptr, nullptr);

        if (r != CL_SUCCESS)
        {
            return false;
        }

        r = CL_SUCCESS;
        r |= clSetKernelArg(dev.kernel, 2, sizeof(int), &cx);
        r |= clSetKernelArg(dev.kernel, 3, sizeof(int), &cz);

        if (r != CL_SUCCESS)
        {
            return false;
        }

        size_t globalSize = TerrainAlgorithm::COLUMN_COUNT;

        r =
            clEnqueueNDRangeKernel(
                dev.queue, dev.kernel, 1, nullptr,
                &globalSize, nullptr, 0, nullptr, nullptr);

        if (r != CL_SUCCESS)
        {
            return false;
        }

        float localOut[TerrainAlgorithm::COLUMN_COUNT];

        r =
            clEnqueueReadBuffer(
                dev.queue, dev.outputBuffer, CL_TRUE, 0,
                sizeof(float) * TerrainAlgorithm::COLUMN_COUNT,
                localOut, 0, nullptr, nullptr);

        if (r != CL_SUCCESS)
        {
            return false;
        }

        g_sink += localOut[(cx & 15) + (cz & 15) * 16];

        return true;
    };

    for (int i = 0; i < kWarmupChunks; ++i)
    {
        if (!runChunk(i, -i))
        {
            /*
             * Release the pipeline only. Context and queue are owned
             * by Initialize()/Shutdown(); destroying them here would
             * permanently kill a device that hiccups once during
             * benchmarking (regression: LTE-BUG-BENCH-TEARDOWN).
             */
            dev.healthy = false;
            dev.cooldownUntilMs = NowMs() + kCooldownMs;
            ReleasePipeline(dev);
            return 0;
        }
    }

    const auto start = std::chrono::steady_clock::now();

    for (int i = 0; i < kBenchmarkChunks; ++i)
    {
        if (!runChunk(i, -i))
        {
            /*
             * Same contract as the warmup path: pipeline-only release.
             * The half-open retry in ProcessTerrain rebuilds the
             * pipeline after the cooldown and the device self-heals.
             */
            dev.healthy = false;
            dev.cooldownUntilMs = NowMs() + kCooldownMs;
            ReleasePipeline(dev);
            return 0;
        }
    }

    const auto end = std::chrono::steady_clock::now();

    const double seconds =
        std::chrono::duration<double>(end - start).count();

    if (seconds <= 0.0)
    {
        return 0;
    }

    return static_cast<long>(
        static_cast<double>(kBenchmarkChunks) / seconds);
}

} // namespace LTE

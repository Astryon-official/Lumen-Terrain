#pragma once

#include <CL/cl.h>

#include <string>
#include <vector>

namespace LTE
{

struct OpenCLDeviceInfo
{
    std::string name;
    std::string vendor;
    std::string driverVersion;
    std::string openclVersion;
    std::string platformName;

    cl_platform_id platform = nullptr;
    cl_device_id device = nullptr;

    unsigned int computeUnits = 0;
    unsigned long long globalMemory = 0;
    unsigned long long localMemory = 0;

    bool integrated = false;
};

/*
 * Multi-device OpenCL runtime (LTE 2.0).
 *
 * Owns EVERYTHING per device:
 *   context, command queue, program, kernel, input/output buffers,
 *   health state.
 *
 * Ownership contract:
 *   - One host thread drives one device at a time (the Java scheduler
 *     guarantees this). No internal locking on the hot path.
 *   - Initialize()/Shutdown() are the only globally synchronized ops.
 *   - A failure on device N marks ONLY device N unhealthy; every other
 *     device keeps processing.
 *   - Unhealthy devices are retried after a cooldown (half-open probe),
 *     so a transient driver hiccup does not permanently disable a GPU.
 */
class DeviceRuntime
{
public:

    /*
     * Enumerate all usable non-CPU OpenCL devices across all platforms
     * and create a context + command queue for each. Idempotent.
     *
     * Returns the number of initialized devices (0 = no OpenCL at all,
     * which is a valid configuration - Java falls back to CPU).
     */
    static int Initialize();

    /* Release every per-device resource. Safe to call twice. */
    static void Shutdown();

    /* True after a successful Initialize() with >= 1 device. */
    static bool IsInitialized();

    /* Number of enumerated devices. */
    static int DeviceCount();

    /* Info snapshot for a device (empty struct when out of range). */
    static const OpenCLDeviceInfo& GetDeviceInfo(int index);

    /*
     * Process one 16x16 chunk on a specific device.
     * Returns false (and marks the device unhealthy) on ANY error.
     */
    static bool ProcessTerrain(
        int deviceIndex,
        int x,
        int z,
        const int* heightmap,
        double* output);

    /*
     * Benchmark one device with the identical terrain workload used by
     * CpuCompute::RunBenchmark (chunks/second). 0 when unavailable.
     */
    static long RunBenchmark(int deviceIndex);

    /*
     * Health queries for the scheduler.
     */
    static bool IsHealthy(int index);

    /* Manually disable/enable a device (config escape hatch). */
    static void SetEnabled(int index, bool enabled);
    static bool IsEnabled(int index);

};

} // namespace LTE

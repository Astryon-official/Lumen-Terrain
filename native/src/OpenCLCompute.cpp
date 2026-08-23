#include "OpenCLCompute.h"
#include "OpenCLManager.h"
#include "DeviceRuntime.h"
#include "TerrainAlgorithm.h"
#include "Logger.h"

namespace LTE
{

namespace
{

/*
 * Benchmark sink: accumulates a value derived from every measured
 * chunk so the optimizer cannot elide the benchmark workload.
 */
double g_sink = 0.0;

} // namespace


bool OpenCLCompute::EnsureKernel()
{
    /*
     * Kernel/buffer construction moved into DeviceRuntime (per device).
     * Kept as a validity probe for legacy callers.
     */
    return DeviceRuntime::IsInitialized();
}


bool OpenCLCompute::ProcessTerrain(
    int x,
    int z,
    const int* heightmap,
    double* output
)
{
    /*
     * Legacy single-device entry point -> device 0.
     */
    return DeviceRuntime::ProcessTerrain(0, x, z, heightmap, output);
}


long OpenCLCompute::RunBenchmark()
{
    if (!DeviceRuntime::IsInitialized())
    {
        return 0;
    }

    long best = 0;

    for (int i = 0; i < DeviceRuntime::DeviceCount(); ++i)
    {
        const long score = DeviceRuntime::RunBenchmark(i);

        if (score > best)
        {
            best = score;
        }
    }

    return best;
}


void OpenCLCompute::ReleaseResources()
{
    /*
     * Per-device resources are owned and released by DeviceRuntime.
     * Nothing cached at this level anymore.
     */
}

} // namespace LTE

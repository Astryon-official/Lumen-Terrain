#include "LTE.h"

#include "Logger.h"
#include "DeviceRuntime.h"
#include "OpenCLCompute.h"
#include "CpuCompute.h"

namespace LTE
{

static bool initialized = false;


/*
 * Initialize LTE native core.
 *
 * OpenCL device enumeration + per-device contexts happen exactly once,
 * here. Every usable non-CPU device across every OpenCL platform gets
 * its own context/queue so devices can run truly concurrently.
 *
 * Failure is never fatal: Java degrades to the CPU backend.
 */
bool Initialize()
{
    if (initialized)
    {
        return true;
    }

    Log("Initializing LTE native core");


    const int devices = DeviceRuntime::Initialize();

    if (devices <= 0)
    {
        /*
         * Valid configuration (headless servers, no ICD, CPU-only):
         * the mod keeps running with Java/native CPU processing.
         */
        Log("OpenCL unavailable - GPU backend disabled");
    }
    else
    {
        for (int i = 0; i < devices; ++i)
        {
            const OpenCLDeviceInfo& info = DeviceRuntime::GetDeviceInfo(i);

            Log(("GPU " + std::to_string(i) + ": "
                 + info.name).c_str());
        }
    }


    initialized = true;

    return true;
}


void Shutdown()
{
    if (!initialized)
    {
        return;
    }

    Log("Shutting down LTE native core");

    OpenCLCompute::ReleaseResources();
    DeviceRuntime::Shutdown();

    initialized = false;
}


bool IsInitialized()
{
    return initialized;
}


const char* GetVersion()
{
    return "2.1.0";
}


long RunGPUBenchmark()
{
    if (!DeviceRuntime::IsInitialized())
    {
        return 0;   // no usable OpenCL device
    }

    long total = 0;

    for (int i = 0; i < DeviceRuntime::DeviceCount(); ++i)
    {
        total += DeviceRuntime::RunBenchmark(i);
    }

    if (total > 0)
    {
        Log("GPU benchmark complete");
    }

    return total;
}

} // namespace LTE

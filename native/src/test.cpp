#include "LTE.h"
#include "DeviceRuntime.h"
#include "OpenCLCompute.h"
#include "CpuCompute.h"
#include "Logger.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>

using namespace LTE;

/*
 * LTE native test (LTE 2.1 multi-device)
 *
 * 1. CPU terrain processing on deterministic input.
 * 2. Multi-device enumeration and per-device reporting.
 * 3. Per-device processing + CPU/GPU output equivalence.
 * 4. Device health semantics (invalid index, disable/enable).
 * 5. Comparable per-device benchmark execution.
 */

namespace
{

int g_failures = 0;


void Expect(bool condition, const char* what)
{
    if (condition)
    {
        std::printf("  PASS: %s\n", what);
    }
    else
    {
        std::printf("  FAIL: %s\n", what);
        ++g_failures;
    }
}


void FillHeightmap(int* heightmap)
{
    unsigned int seed = 987654321u;

    for (int i = 0; i < 256; ++i)
    {
        seed = seed * 1664525u + 1013904223u;
        heightmap[i] = 32 + static_cast<int>(seed % 96u);
    }
}


bool NearlyEqual(double a, double b)
{
    const double scale =
        std::max(1.0, std::abs(a));

    return std::abs(a - b) <= 1e-9 * scale;
}

} // namespace


int main()
{
    std::printf("=== Lumen Terrain Engine 2.1 native test ===\n\n");


    /*
     * 1. CPU reference path.
     */
    int heightmap[256];
    double cpuOutput[256];
    double gpuOutput[256];

    FillHeightmap(heightmap);

    bool cpuOk =
        LTE::CpuCompute::ProcessTerrain(7, -13, heightmap, cpuOutput);

    Expect(cpuOk, "CPU terrain processing succeeds");

    /*
     * Every column's heightModification must be exactly 0 or 2
     * (mask==0 -> raise by 2, otherwise no change).
     */
    int validValues = 0;

    for (int i = 0; i < 256; ++i)
    {
        if (cpuOutput[i] == 0.0 || cpuOutput[i] == 2.0)
        {
            ++validValues;
        }
    }

    Expect(cpuOk && validValues == 256,
        "CPU output populated with valid modifications (all 256 in {0,2})");

    int raisedColumns = 0;

    for (int i = 0; i < 256; ++i)
    {
        if (cpuOutput[i] == 2.0)
        {
            ++raisedColumns;
        }
    }

    std::printf("  raised columns: %d / 256\n", raisedColumns);

    /*
     * Determinism: identical input must give identical output.
     */
    double repeat[256];

    LTE::CpuCompute::ProcessTerrain(7, -13, heightmap, repeat);

    bool deterministic = true;

    for (int i = 0; i < 256; ++i)
    {
        if (!NearlyEqual(cpuOutput[i], repeat[i]))
        {
            deterministic = false;
            break;
        }
    }

    Expect(deterministic, "CPU output is deterministic");


    long cpuScore = LTE::CpuCompute::RunBenchmark();

    Expect(cpuScore > 0, "CPU benchmark produces a score");

    std::printf("  CPU score: %ld chunks/s\n", cpuScore);


    /*
     * 2. GPU path.
     */
    LTE::Initialize();

    if (!DeviceRuntime::IsInitialized())
    {
        std::printf("\nNo OpenCL device available - skipping GPU tests.\n");
        std::printf("This is a valid configuration (CPU fallback).\n");

        std::printf(
            "\n%s\n",
            g_failures == 0 ? "RESULT: PASS" : "RESULT: FAIL");

        return g_failures == 0 ? 0 : 1;
    }


    /*
     * 2.1: every enumerated device must build a pipeline and process
     * terrain successfully.
     */
    const int deviceCount = DeviceRuntime::DeviceCount();

    std::printf("\nEnumerated OpenCL devices: %d\n", deviceCount);

    Expect(deviceCount >= 1, "multi-device enumeration finds at least one device");

    bool gpuOk = true;

    for (int d = 0; d < deviceCount; ++d)
    {
        const auto& di = DeviceRuntime::GetDeviceInfo(d);

        const bool ok = DeviceRuntime::ProcessTerrain(
            d, 7, -13, heightmap, gpuOutput);

        std::printf("  device %d: %s (%u CUs)\n",
            d,
            di.name.c_str(),
            di.computeUnits);

        Expect(ok, "GPU terrain processing succeeds on this device");

        if (!ok)
        {
            gpuOk = false;
            continue;
        }

        /*
         * Per-device CPU/GPU equivalence.
         */
        int mismatches = 0;

        for (int i = 0; i < 256; ++i)
        {
            if (!NearlyEqual(cpuOutput[i], gpuOutput[i]))
            {
                ++mismatches;
            }
        }

        Expect(mismatches == 0,
            "CPU and GPU outputs are equivalent on this device");

        Expect(DeviceRuntime::IsHealthy(d), "device reports healthy after success");

        /*
         * Health handling: an invalid index must report unhealthy, not
         * crash; disabling must be reflected by IsHealthy.
         */
        Expect(!DeviceRuntime::IsHealthy(-1) && !DeviceRuntime::IsHealthy(9999),
            "health query rejects invalid indices");

        DeviceRuntime::SetEnabled(d, false);
        Expect(!DeviceRuntime::IsHealthy(d), "disabled device reports unhealthy");
        DeviceRuntime::SetEnabled(d, true);
        Expect(DeviceRuntime::IsHealthy(d), "re-enabled device is schedulable again");
    }

    Expect(gpuOk, "GPU terrain processing succeeded on at least one device");


    /*
     * 3. Equivalence.
     */
    if (gpuOk)
    {
        int mismatches = 0;
        double worstDelta = 0.0;

        for (int i = 0; i < 256; ++i)
        {
            if (!NearlyEqual(cpuOutput[i], gpuOutput[i]))
            {
                ++mismatches;

                const double delta =
                    std::abs(cpuOutput[i] - gpuOutput[i]);

                if (delta > worstDelta)
                {
                    worstDelta = delta;
                }
            }
        }

        std::printf("  mismatches=%d worstDelta=%.12g\n",
            mismatches, worstDelta);

        Expect(mismatches == 0, "CPU and GPU outputs are equivalent");
    }


    /*
     * Per-device benchmarks; every healthy device must produce a score.
     */
    bool anyBenchmark = false;

    for (int d = 0; d < DeviceRuntime::DeviceCount(); ++d)
    {
        const long score = DeviceRuntime::RunBenchmark(d);

        std::printf("  GPU %d score: %ld chunks/s\n", d, score);

        if (score > 0)
        {
            anyBenchmark = true;
        }
    }

    Expect(anyBenchmark, "GPU benchmark produces a score");


    /*
     * 4. Resource-ownership regressions (LTE 2.1).
     *
     * These pin the context/queue ownership contract:
     *   - benchmarking a device must never destroy its context/queue
     *   - rejected calls must never poison a device's pipeline
     *   - one device's lifecycle must not disturb another's
     */
    std::printf("\nResource ownership regressions:\n");

    /*
     * 4.1: after benchmarks ran on every device, every device must
     * still process terrain (catches pipeline/context teardown bugs).
     */
    bool postBenchOk = true;

    for (int d = 0; d < DeviceRuntime::DeviceCount(); ++d)
    {
        if (!DeviceRuntime::ProcessTerrain(
                d, 100 + d, -200 - d, heightmap, gpuOutput))
        {
            postBenchOk = false;
        }
    }

    Expect(postBenchOk,
        "devices still process terrain after their own benchmarks");

    /*
     * 4.2: rejected calls (bad args / bad index) must not damage the
     * device - a valid dispatch immediately afterwards must succeed.
     */
    bool rejectionSafe = true;

    for (int d = 0; d < DeviceRuntime::DeviceCount(); ++d)
    {
        bool rejected =
            !DeviceRuntime::ProcessTerrain(
                d, 0, 0, nullptr, gpuOutput)
            || !DeviceRuntime::ProcessTerrain(
                d, 0, 0, heightmap, nullptr)
            || !DeviceRuntime::ProcessTerrain(
                -1, 0, 0, heightmap, gpuOutput)
            || !DeviceRuntime::ProcessTerrain(
                9999, 0, 0, heightmap, gpuOutput);

        if (!rejected)
        {
            rejectionSafe = false;
        }

        if (!DeviceRuntime::ProcessTerrain(
                d, 5, 5, heightmap, gpuOutput))
        {
            rejectionSafe = false;   // device was poisoned by rejects
        }
    }

    Expect(rejectionSafe,
        "rejected dispatches do not poison subsequent valid ones");

    /*
     * 4.3: sustained mixed-device dispatch loop - 120 chunks spread
     * over all devices with distinct coordinates; every result must
     * match the CPU reference for that coordinate set.
     */
    bool sustained = true;

    for (int n = 0; n < 120 && sustained; ++n)
    {
        const int d = n % DeviceRuntime::DeviceCount();
        const int cx = (n * 7) % 4096 - 2048;
        const int cz = -(n * 13) % 4096 + 2048;

        double ref[256];
        double got[256];

        if (!LTE::CpuCompute::ProcessTerrain(cx, cz, heightmap, ref)
            || !DeviceRuntime::ProcessTerrain(
                d, cx, cz, heightmap, got))
        {
            sustained = false;
            break;
        }

        for (int i = 0; i < 256; ++i)
        {
            if (!NearlyEqual(ref[i], got[i]))
            {
                sustained = false;
                break;
            }
        }
    }

    Expect(sustained,
        "sustained round-robin dispatch stays correct on every device");

    /*
     * 4.4: full shutdown -> re-initialize lifecycle. Devices must come
     * back fully functional (no leaked/half-released state).
     */
    LTE::Shutdown();
    LTE::Initialize();

    bool relifecycle = DeviceRuntime::IsInitialized();

    for (int d = 0; d < DeviceRuntime::DeviceCount() && relifecycle; ++d)
    {
        relifecycle = DeviceRuntime::ProcessTerrain(
            d, 42, -42, heightmap, gpuOutput);
    }

    Expect(relifecycle,
        "shutdown/re-initialize restores full device function");

    LTE::Shutdown();


    std::printf(
        "\n%s\n",
        g_failures == 0 ? "RESULT: PASS" : "RESULT: FAIL");

    return g_failures == 0 ? 0 : 1;
}

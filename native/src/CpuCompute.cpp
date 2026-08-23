#include "CpuCompute.h"
#include "TerrainAlgorithm.h"

#include <chrono>

namespace LTE
{

namespace
{

/*
 * Benchmark sink: prevents the optimizer from eliding measured work.
 */
double g_sink = 0.0;

} // namespace

bool CpuCompute::ProcessTerrain(
    int x,
    int z,
    const int* heightmap,
    double* output
)
{
    if (heightmap == nullptr || output == nullptr)
    {
        return false;
    }

    TerrainAlgorithm::RunChunk(heightmap, x, z, output);

    return true;
}


long CpuCompute::RunBenchmark()
{
    /*
     * Deterministic LCG heightmap: same workload shape as real chunks.
     * 400 chunks give a stable measurement without stalling startup.
     */
    constexpr int kChunks = 400;

    int heightmap[TerrainAlgorithm::COLUMN_COUNT];
    double output[TerrainAlgorithm::COLUMN_COUNT];

    unsigned int seed = 12345u;

    for (int i = 0; i < TerrainAlgorithm::COLUMN_COUNT; ++i)
    {
        seed = seed * 1664525u + 1013904223u;
        heightmap[i] = 32 + static_cast<int>(seed % 64u);
    }


    const auto start =
        std::chrono::steady_clock::now();

    /*
     * g_sink accumulates a value derived from every measured chunk so
     * the optimizer cannot elide the workload (same as GPU benchmark).
     */
    for (int c = 0; c < kChunks; ++c)
    {
        TerrainAlgorithm::RunChunk(heightmap, c, -c, output);

        g_sink += output[(c & 15) + ((-c & 15) * 16)];
    }

    const auto end =
        std::chrono::steady_clock::now();


    const double seconds =
        std::chrono::duration<double>(end - start).count();

    if (seconds <= 0.0)
    {
        return 0;
    }


    return static_cast<long>(
        static_cast<double>(kChunks) / seconds);
}

} // namespace LTE

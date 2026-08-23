#pragma once

/*
 * CPU terrain-processing backend (reference implementation).
 *
 * Executes TerrainAlgorithm on the host with no allocations and no
 * shared mutable state, so it is deterministic and thread-safe.
 */

namespace LTE
{

class CpuCompute
{
public:

    /*
     * Process a full 16x16 chunk (256 columns) on the CPU.
     * Returns false only on invalid arguments.
     */
    static bool ProcessTerrain(
        int x,
        int z,
        const int* heightmap,
        double* output
    );

    /*
     * Benchmark: process representative chunks with the identical
     * terrain algorithm used by the GPU benchmark.
     *
     * Returns normalized LTE score in chunks/second; 0 on failure.
     */
    static long RunBenchmark();

};

} // namespace LTE

#pragma once

namespace LTE
{

/*
 * OpenCL GPU terrain-processing backend.
 *
 * Resources are created once and reused for every chunk:
 *   program -> kernel -> heightmap buffer -> output buffer
 *
 * Every failure returns false; the Java caller then falls back to CPU.
 */
class OpenCLCompute
{
public:

    /*
     * Compile the terrain kernel once and allocate reusable buffers.
     * Safe to call repeatedly; only the first successful call does work.
     */
    static bool EnsureKernel();

    /*
     * Process a full 16x16 chunk on the GPU.
     */
    static bool ProcessTerrain(
        int x,
        int z,
        const int* heightmap,
        double* output
    );

    /*
     * Benchmark: identical workload to CpuCompute::RunBenchmark so
     * scores are directly comparable (chunks/second).
     */
    static long RunBenchmark();

    /*
     * Release cached kernel/program/buffers. Called from LTE::Shutdown().
     */
    static void ReleaseResources();

};

} // namespace LTE

#pragma once

#include <cmath>

/*
 * LTE terrain algorithm - single source of truth.
 *
 * Mirrors the Java pipeline in TerrainProcessor:
 *   heightmap -> slopeMap -> terrainMask -> heightModification
 *
 * Included by BOTH:
 *   - native/src/CpuCompute.cpp    (CPU backend)
 *   - native/src/OpenCLCompute.cpp (GPU backend host code + kernel source)
 *
 * All math is integer compare / fp32 divide - exact on every OpenCL
 * device including FP64-less integrated GPUs, and bit-identical
 * between CPU and GPU.
 *
 * Contract (fixed):
 *   - Input:  int heightmap[256]      (16x16 column heights,
 *                                      index = z * 16 + x)
 *   - Output: double result[256]      (heightModification per column)
 */

namespace LTE
{

class TerrainAlgorithm
{
public:

    static constexpr int CHUNK_SIZE = 16;
    static constexpr int COLUMN_COUNT = 256;

    /*
     * Host math helpers mirroring OpenCL intrinsics.
     */
    static float ToFloat(double v) { return static_cast<float>(v); }
    static float Min(float a, float b) { return a < b ? a : b; }

    /*
     * Reference CPU implementation of the terrain kernel.
     *
     * Deterministic, thread-safe (no shared state), no allocations.
     */
    static void RunColumn(
        const int* heightmap,
        int chunkX,
        int chunkZ,
        int index,
        double* output
    )
    {
        (void)chunkX;   // reserved for future world-space features
        (void)chunkZ;

        const int x = index % CHUNK_SIZE;
        const int z = index / CHUNK_SIZE;

        const int current = heightmap[index];

        /*
         * Slope: absolute height delta against +X and +Z neighbours.
         */
        int slope = 0;

        if (x < CHUNK_SIZE - 1)
        {
            slope += std::abs(current - heightmap[index + 1]);
        }

        if (z < CHUNK_SIZE - 1)
        {
            slope += std::abs(current - heightmap[index + CHUNK_SIZE]);
        }


        /*
         * Terrain mask: normalized slope in [0,1].
         */
        const float mask =
            Min(ToFloat(static_cast<double>(slope)) / 20.0f, 1.0f);


        /*
         * Height modification: flat areas are raised by 2.
         */
        output[index] = (mask == 0.0f) ? 2.0 : 0.0;
    }

    /*
     * Process all 256 columns on the CPU.
     */
    static void RunChunk(
        const int* heightmap,
        int chunkX,
        int chunkZ,
        double* output
    )
    {
        for (int i = 0; i < COLUMN_COUNT; ++i)
        {
            RunColumn(heightmap, chunkX, chunkZ, i, output);
        }
    }

    /*
     * OpenCL C kernel source implementing RunColumn above.
     *
     * Kept in this header so the algorithm cannot drift between
     * the CPU and GPU implementations without an explicit edit here.
     */
    static constexpr const char* KERNEL_SOURCE = R"CLC(

__kernel void generateTerrain(
    __global const int*  heightmap,
    __global float*      output,
    const int chunkX,
    const int chunkZ)
{
    const int index = get_global_id(0);

    if (index >= 256)
        return;

    const int x = index % 16;
    const int z = index / 16;
    const int current = heightmap[index];

    int slope = 0;
    if (x < 15) slope += abs(current - heightmap[index + 1]);
    if (z < 15) slope += abs(current - heightmap[index + 16]);

    const float mask = min((float)slope / 20.0f, 1.0f);

    output[index] = (mask == 0.0f) ? 2.0f : 0.0f;
}

)CLC";
};

} // namespace LTE

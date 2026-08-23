package com.astryon.lte.terrain;

import com.astryon.lte.compute.LumenChunkComputeData;
import com.astryon.lte.config.LTEConfig;

/*
 * CPU terrain backend.
 *
 * Hot-path rules (LTE 2.1):
 *   - zero System.out traffic unless LTEConfig.verbose is set
 *   - intermediate buffers (slopeMap / terrainMask) come from a
 *     ThreadLocal scratch pool - no per-chunk allocation
 */
public class TerrainProcessor {

    /**
     * Per-thread scratch reused across chunks. Two arrays per worker
     * thread, allocated once. heightModification is NOT pooled: it is
     * part of the chunk's output contract.
     */
    private static final ThreadLocal<double[][]> SCRATCH =
            ThreadLocal.withInitial(() -> new double[][] {
                    new double[256], new double[256]
            });


    public static void prepare(
            LumenChunkComputeData data
    ) {
        // Intentionally silent: called per chunk.
    }



    public static void cpuProcess(
            LumenChunkComputeData data
    ) {

        long start =
                System.nanoTime();

        double[][] scratch = SCRATCH.get();
        double[] slopeMap = scratch[0];
        double[] terrainMask = scratch[1];

        generateSlopeMap(data.heightmap, slopeMap);

        generateTerrainMask(slopeMap, terrainMask);

        generateHeightModification(terrainMask, data.heightModification);

        data.markCPUComplete();

        if (LTEConfig.verbose) {

            double ms =
                    (System.nanoTime() - start) / 1_000_000.0;

            System.out.println(
                "[LTE] CPU terrain processed: "
                + data.chunkX + ", " + data.chunkZ
                + " (" + ms + " ms)"
            );
        }

    }


    private static void generateHeightModification(
            double[] terrainMask,
            double[] out
    ) {
        for (int i = 0; i < 256; i++) {
            out[i] = terrainMask[i] == 0 ? 2 : 0;
        }
    }


    private static void generateSlopeMap(
            int[] heightmap,
            double[] slopeMap
    ) {
        for (int z = 0; z < 16; z++) {

            int rowBase = z * 16;

            for (int x = 0; x < 16; x++) {

                int index = rowBase + x;
                int current = heightmap[index];
                double slope = 0;

                if (x < 15) {
                    slope += Math.abs(current - heightmap[index + 1]);
                }

                if (z < 15) {
                    slope += Math.abs(current - heightmap[index + 16]);
                }

                slopeMap[index] = slope;
            }
        }
    }




    private static void generateTerrainMask(
            double[] slopeMap,
            double[] terrainMask
    ) {
        for (int i = 0; i < 256; i++) {
            terrainMask[i] = Math.min(slopeMap[i] / 20.0, 1.0);
        }
    }

}

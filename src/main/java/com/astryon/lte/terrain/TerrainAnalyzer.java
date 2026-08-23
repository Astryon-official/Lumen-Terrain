package com.astryon.lte.terrain;

import com.astryon.lte.chunk.ChunkQueue;
import com.astryon.lte.chunk.CompletedChunkCache;
import com.astryon.lte.chunk.LTEChunkData;

import net.minecraft.world.level.chunk.ChunkAccess;

/*
 * Extracts terrain features from a freshly generated chunk and
 * hands the real heightmap to the compute pipeline.
 *
 * Runs on world-gen threads - must not touch shared mutable state
 * except through thread-safe pipeline classes.
 */
public class TerrainAnalyzer {

    public static void analyze(ChunkAccess chunk) {

        long start = System.nanoTime();

        int x = chunk.getPos().x();
        int z = chunk.getPos().z();


        /*
         * Real 16x16 surface heightmap (index = z * 16 + x).
         */
        LTEHeightmap map = HeightMapBuilder.build(chunk);


        int minHeight = map.getMinHeight();
        int maxHeight = map.getMaxHeight();

        double averageHeight = map.getAverageHeight();

        double variation = maxHeight - minHeight;


        // CPU v2 metrics (temporary calculations)
        double roughness = variation / 10.0;

        double slope = Math.atan(variation / 16.0);

        double complexity =
                (roughness * 50.0)
                + (slope * 50.0);


        String type;

        if (variation < 3) {
            type = "FLAT";
        }
        else if (variation < 15) {
            type = "ROLLING";
        }
        else {
            type = "MOUNTAIN";
        }


        TerrainProfile profile = new TerrainProfile(
                minHeight,
                maxHeight,
                averageHeight,
                variation,
                roughness,
                slope,
                complexity,
                type
        );


        if (com.astryon.lte.config.LTEConfig.verbose) {
            profile.print();
        }


        /*
         * Carry the real heightmap into the pipeline.
         */
        LTEChunkData data = new LTEChunkData(x, z);

        System.arraycopy(
            map.getFlat(),
            0,
            data.heightmap,
            0,
            256
        );

        data.cpuPrepared = true;


        ChunkQueue.addChunk(x, z, data);


        if (com.astryon.lte.config.LTEConfig.verbose) {

            long end = System.nanoTime();

            System.out.println(
                "[LTE] Terrain analysis completed in "
                + ((end - start) / 1_000_000.0)
                + " ms"
            );
        }
    }
}

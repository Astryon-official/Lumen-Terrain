package com.astryon.lte.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/*
 * Extracts the 16x16 surface heightmap from a world chunk.
 * Index convention matches the native contract: index = z * 16 + x.
 */
public class HeightMapBuilder {

    public static LTEHeightmap build(ChunkAccess chunk) {

        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;

        long totalHeight = 0;

        int[] heights = new int[256];

        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();


        for (int z = 0; z < 16; z++) {

            for (int x = 0; x < 16; x++) {

                int surfaceY =
                    findSurface(
                        chunk,
                        startX + x,
                        startZ + z
                    );

                heights[z * 16 + x] = surfaceY;

                minHeight = Math.min(minHeight, surfaceY);
                maxHeight = Math.max(maxHeight, surfaceY);

                totalHeight += surfaceY;
            }
        }


        return new LTEHeightmap(
            heights,
            minHeight,
            maxHeight,
            totalHeight / 256.0
        );
    }


    private static int findSurface(
            ChunkAccess chunk,
            int x,
            int z
    ) {


	int top = 320;

        for (int y = top; y >= -64; y--) {

            if (!chunk.getBlockState(
                    new BlockPos(x, y, z)).isAir()) {

                return y;
            }

        }


        return -64;
    }
}

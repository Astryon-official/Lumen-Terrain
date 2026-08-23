package com.astryon.lte.terrain;

/*
 * Flat, pipeline-native heightmap container (index = z * 16 + x).
 *
 * The flat array is passed straight to the GPU/CPU compute path
 * without any copying or reindexing.
 */
public class LTEHeightmap {

    private final int[] heights;

    private final int minHeight;
    private final int maxHeight;
    private final double averageHeight;


    public LTEHeightmap(
            int[] heights,
            int minHeight,
            int maxHeight,
            double averageHeight
    ) {
        this.heights = heights;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.averageHeight = averageHeight;
    }


    /** Raw flat array; do not mutate. index = z * 16 + x. */
    public int[] getFlat() {
        return heights;
    }


    public int getHeight(int x, int z) {
        return heights[z * 16 + x];
    }


    public int getMinHeight() {
        return minHeight;
    }


    public int getMaxHeight() {
        return maxHeight;
    }


    public double getAverageHeight() {
        return averageHeight;
    }
}

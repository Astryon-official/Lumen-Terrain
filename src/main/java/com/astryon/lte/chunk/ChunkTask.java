package com.astryon.lte.chunk;

public class ChunkTask {

    public final int x;
    public final int z;

    public ChunkState state;

    /*
     * Real terrain data captured at hook time by TerrainAnalyzer.
     * Never null: a task always carries the heightmap it was created
     * with, so the compute path never sees zero-filled data.
     */
    public final LTEChunkData data;

    public ChunkTask(int x, int z, LTEChunkData data) {

        this.x = x;
        this.z = z;

        this.state = ChunkState.PREDICTED;

        this.data = data;

    }
}

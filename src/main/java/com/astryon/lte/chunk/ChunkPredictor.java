package com.astryon.lte.chunk;

import com.astryon.lte.chunk.LTEChunkData;
import net.minecraft.server.level.ServerPlayer;

/*
 * Per-tick player tracking.
 *
 * Marks the chunk each player stands in as "wanted" so the pipeline
 * prioritizes it. Actual prediction of future chunks is handled by
 * the vanilla world-gen system; LTE reacts to what it observes.
 */
public class ChunkPredictor {


    public static void predictPlayer(ServerPlayer player) {


        int chunkX = (int) player.getX() >> 4;
        int chunkZ = (int) player.getZ() >> 4;


        /*
         * The player's own chunk is always wanted immediately.
         */
        if (!CompletedChunkCache.isCompleted(chunkX, chunkZ)
                && !ChunkQueue.contains(chunkX, chunkZ)) {

            ChunkQueue.addChunk(
                chunkX,
                chunkZ,
                new LTEChunkData(chunkX, chunkZ)
            );
        }

    }
}

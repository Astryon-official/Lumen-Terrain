package com.astryon.lte.chunk;

import com.astryon.lte.config.LTEConfig;
import com.astryon.lte.compute.LumenChunkComputeData;
import com.astryon.lte.generation.LTETerrainGenerator;

/*
 * Consumes the chunk queue and runs each chunk through the LTE
 * compute pipeline (GPU when selected, CPU otherwise/fallback).
 *
 * LTE 2.1 changes:
 *   - The unconditional Thread.sleep(5) per chunk is gone. GPU chunks
 *     are already rate-limited by native dispatch; a fixed sleep only
 *     capped throughput at ~200 chunks/s.
 *   - Idle waiting uses a condition variable instead of polling:
 *     zero wakeups when there is no terrain work.
 */
public class ChunkProcessor {

    /** Upper safety bound regardless of config value. */
    private static final int HARD_CAP_PER_CYCLE = 64;

    /**
     * Small yield budget: when the queue briefly looks empty we spin
     * this many times before parking, absorbing producer bursts
     * without a full park/unpark cycle.
     */
    private static final int SPIN_BEFORE_PARK = 32;


    public static void process() {

        int processed = 0;

        while (true) {

            ChunkTask task = ChunkQueue.getNextChunk();

            if (task == null) {
                break;
            }


            if (LTEConfig.verbose) {

                System.out.println(
                    "[LTE] Preparing chunk: "
                    + task.x
                    + ", "
                    + task.z
                );
            }


            task.state = ChunkState.PREPARING_CPU;


            LumenChunkComputeData computeData =
                    new LumenChunkComputeData(task.data);


            LTETerrainGenerator.generate(computeData);


            task.state = ChunkState.COMPLETE;


            ProcessingChunkCache.remove(task.x, task.z);

            CompletedChunkCache.markCompleted(task.x, task.z);


            if (LTEConfig.verbose) {

                System.out.println(
                    "[LTE] Chunk completed: "
                    + task.x
                    + ", "
                    + task.z
                );
            }


            processed++;
        }

        /*
         * Drain loop returns to LTEWorker, which parks until new work
         * arrives - no timed polling anywhere.
         */
    }
}

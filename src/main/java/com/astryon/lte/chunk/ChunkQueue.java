package com.astryon.lte.chunk;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Cross-thread chunk work queue (LTE 2.1).
 *
 * Producers: server world-gen threads (via the mixin).
 * Consumer:  LTE worker thread(s).
 *
 * Performance notes vs. the 2.0 implementation:
 *   - O(1) duplicate detection via a packed-coordinate hash set
 *     instead of an O(n) scan under a global lock.
 *   - Lock-free enqueue/dequeue (ConcurrentLinkedQueue); no monitor
 *     contention with world-gen threads.
 *   - Drop/duplicate log lines are rate-limited so a saturated queue
 *     cannot turn into a log flood.
 */
public class ChunkQueue {

    private static final Queue<ChunkTask> queuedChunks =
            new ConcurrentLinkedQueue<>();

    /** Packed coords currently queued or being processed. */
    private static final ConcurrentHashMap<Long, Boolean> inFlight =
            new ConcurrentHashMap<>();

    private static final AtomicInteger SIZE = new AtomicInteger();

    /** Log rate limiting. */
    private static volatile long lastDropLogMs = 0L;
    private static volatile long lastDupLogMs = 0L;
    private static final long LOG_INTERVAL_MS = 5000L;


    /*
     * Configurable via config/lte.properties (maxQueueSize).
     */
    private static int maxQueueSize() {
        return Math.max(16,
            com.astryon.lte.config.LTEConfig.maxQueueSize);
    }


    private static long pack(int chunkX, int chunkZ) {
        return ((chunkX & 0xFFFFFFFFL) << 32)
                | (chunkZ & 0xFFFFFFFFL);
    }


    public static boolean addChunk(
            int chunkX,
            int chunkZ,
            LTEChunkData data
    ) {

        if (SIZE.get() >= maxQueueSize()) {

            long now = System.currentTimeMillis();

            if (now - lastDropLogMs >= LOG_INTERVAL_MS) {

                lastDropLogMs = now;

                System.out.println(
                    "[LTE] Queue full (" + maxQueueSize()
                    + ") - dropping chunks");
            }

            return false;
        }


        Long key = pack(chunkX, chunkZ);

        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {

            long now = System.currentTimeMillis();

            if (now - lastDupLogMs >= LOG_INTERVAL_MS) {

                lastDupLogMs = now;

                System.out.println(
                    "[LTE] Skipping duplicate chunks");
            }

            return false;
        }


        ChunkTask task = new ChunkTask(chunkX, chunkZ, data);

        ProcessingChunkCache.add(chunkX, chunkZ);

        queuedChunks.add(task);
        SIZE.incrementAndGet();

        /*
         * Wake the worker (parked on SIGNAL when idle).
         */
        com.astryon.lte.core.LTEWorker.signal();


        return true;
    }

    public static ChunkTask getNextChunk() {

        ChunkTask task = queuedChunks.poll();

        if (task != null) {
            SIZE.decrementAndGet();
            inFlight.remove(pack(task.x, task.z));
        }

        return task;
    }


    public static boolean contains(int chunkX, int chunkZ) {
        return inFlight.containsKey(pack(chunkX, chunkZ));
    }


    public static int getSize() {
        return SIZE.get();
    }
}

package com.astryon.lte.chunk;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Tracks completed chunks so world-gen does not re-submit them.
 *
 * LTE 2.1: bounded FIFO eviction. The 2.0 implementation grew one
 * entry per processed chunk for the lifetime of the server. Capacity
 * is maxQueueSize * 8 (default 4096) - far beyond any realistic
 * in-flight window, but strictly bounded.
 *
 * Packed long keys: no allocation on the query path.
 */
public class CompletedChunkCache {

    private static final int CAPACITY =
            Math.max(256,
                com.astryon.lte.config.LTEConfig.maxQueueSize * 8);

    private static final ConcurrentHashMap<Long, Boolean> completed =
            new ConcurrentHashMap<>(CAPACITY * 2);

    /** FIFO order for eviction; touched only by mark/remove paths. */
    private static final ArrayDeque<Long> ORDER = new ArrayDeque<>();


    private static long key(int x, int z) {
        return ((x & 0xFFFFFFFFL) << 32) | (z & 0xFFFFFFFFL);
    }


    public static synchronized void markCompleted(int x, int z) {

        Long k = key(x, z);

        if (completed.putIfAbsent(k, Boolean.TRUE) == null) {

            ORDER.addLast(k);

            while (ORDER.size() > CAPACITY) {

                Long oldest = ORDER.pollFirst();

                if (oldest != null) {
                    completed.remove(oldest);
                }
            }
        }
    }


    public static boolean isCompleted(int x, int z) {
        return completed.containsKey(key(x, z));
    }


    public static int getSize() {
        return completed.size();
    }
}

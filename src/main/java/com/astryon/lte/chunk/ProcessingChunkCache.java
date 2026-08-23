package com.astryon.lte.chunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Tracks chunks currently queued or being processed.
 * Accessed from world-gen threads and the worker thread(s).
 *
 * Packed long keys: zero allocation per lookup (2.0 built a String
 * for every add/contains/remove call).
 */
public class ProcessingChunkCache {

    private static final Set<Long> processingChunks =
            ConcurrentHashMap.newKeySet();


    private static long key(int x, int z) {
        return ((x & 0xFFFFFFFFL) << 32) | (z & 0xFFFFFFFFL);
    }


    public static boolean contains(int x, int z) {
        return processingChunks.contains(key(x, z));
    }


    public static void add(int x, int z) {
        processingChunks.add(key(x, z));
    }


    public static void remove(int x, int z) {
        processingChunks.remove(key(x, z));
    }
}

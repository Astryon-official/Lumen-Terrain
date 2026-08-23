package com.astryon.lte.monitor;

import java.util.concurrent.atomic.AtomicLong;

/*
 * Global chunk statistics (LTE 2.1).
 *
 * Lock-free: relaxed AtomicLong adds on the hot path, safe under any
 * number of concurrent worker threads. Supersedes the 2.0
 * synchronized implementation and the duplicated LTEProfiler.
 */
public final class LTEStats {

    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong TOTAL_NS = new AtomicLong();

    /** Slow-path counters for periodic status output. */
    public static final AtomicLong GPU_CHUNKS = new AtomicLong();
    public static final AtomicLong CPU_CHUNKS = new AtomicLong();

    private LTEStats() {
    }

    /**
     * Records one completed chunk.
     *
     * @param timeNanos wall time of the whole backend dispatch
     */
    public static void chunkCompleted(long timeNanos) {

        CHUNKS.incrementAndGet();
        TOTAL_NS.addAndGet(timeNanos);
    }

    /** Records which backend actually produced a chunk. */
    public static void recordBackend(boolean gpu) {
        if (gpu) {
            GPU_CHUNKS.incrementAndGet();
        } else {
            CPU_CHUNKS.incrementAndGet();
        }
    }

    public static long getChunksCompleted() {
        return CHUNKS.get();
    }

    /** Average processing time in nanoseconds, 0 when empty. */
    public static long getAverageTimeNanos() {

        long chunks = CHUNKS.get();

        if (chunks == 0) {
            return 0;
        }

        return TOTAL_NS.get() / chunks;
    }

    /** Multi-line summary for status output. */
    public static String snapshot() {

        return "[LTE] stats: chunks=" + CHUNKS.get()
                + " avg=" + getAverageTimeNanos() / 1_000_000.0 + "ms"
                + " gpu=" + GPU_CHUNKS.get()
                + " cpu=" + CPU_CHUNKS.get();
    }
}

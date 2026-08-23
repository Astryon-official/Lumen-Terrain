package com.astryon.lte.core;

import com.astryon.lte.chunk.ChunkProcessor;
import com.astryon.lte.chunk.ChunkQueue;
import com.astryon.lte.config.LTEConfig;

/*
 * LTE worker pool (LTE 2.1).
 *
 * One to N threads drain the shared chunk queue. Sizing:
 *   "auto" -> min(4, max(1, cores / 4))
 *   explicit config always wins.
 *
 * Why a cap of 4: each GPU device is claimed exclusively while
 * processing (DeviceScheduler busy flags), so more workers than
 * devices adds no GPU parallelism; the extra threads only cover CPU
 * fallback throughput. Four is enough to saturate one fast GPU's
 * dispatch pipeline plus CPU overflow, without starving the server's
 * own thread pool.
 */
public class LTEWorkerPool {

    private static volatile LTEWorker[] workers = new LTEWorker[0];

    /**
     * Starts the configured number of worker threads. Idempotent:
     * calling twice does not double the pool.
     */
    public static synchronized void start() {

        if (workers.length > 0) {
            return;
        }

        int count = resolveWorkerCount();
        LTEConfig.resolvedWorkerThreads = count;

        LTEWorker[] pool = new LTEWorker[count];

        for (int i = 0; i < count; i++) {

            LTEWorker w = new LTEWorker(
                    count > 1 ? ("LTE-Worker-" + i) : "LTE-Worker");

            w.setPriority(Thread.NORM_PRIORITY - 1);

            pool[i] = w;
            w.start();
        }

        workers = pool;

        System.out.println("[LTE] Worker pool started: "
                + count + " thread(s)");
    }

    /** Signals every worker to exit and wakes them. */
    public static synchronized void shutdown() {

        for (LTEWorker w : workers) {
            w.shutdown();
        }

        com.astryon.lte.core.LTEWorker.wakeAll();
    }

    public static int size() {
        return workers.length;
    }

    private static int resolveWorkerCount() {

        String cfg = LTEConfig.workerThreads;

        if (cfg == null || cfg.isBlank() || cfg.equals("auto")) {

            int cores = Runtime.getRuntime().availableProcessors();

            return Math.min(4, Math.max(1, cores / 4));
        }

        try {
            return Math.min(8,
                Math.max(1, Integer.parseInt(cfg.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}

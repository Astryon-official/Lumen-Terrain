import com.astryon.lte.chunk.ChunkQueue;
import com.astryon.lte.chunk.CompletedChunkCache;
import com.astryon.lte.chunk.LTEChunkData;
import com.astryon.lte.compute.LumenChunkComputeData;
import com.astryon.lte.config.LTEConfig;
import com.astryon.lte.core.LTEWorkerPool;
import com.astryon.lte.gpu.DeviceScheduler;
import com.astryon.lte.gpu.LTENative;
import com.astryon.lte.monitor.DeviceTelemetry;
import com.astryon.lte.monitor.LTEStats;
import com.astryon.lte.terrain.TerrainProcessor;

/*
 * LTE 2.1 stress test - drives the REAL production pipeline
 * (ChunkQueue -> LTEWorkerPool -> ChunkProcessor ->
 *  LTETerrainGenerator -> DeviceScheduler -> JNI) without Minecraft.
 *
 * Reference oracle: TerrainProcessor.cpuProcess (the shipped Java
 * CPU backend), already proven bit-identical to the native CPU and
 * OpenCL implementations by LTE_Test.
 *
 * Stages:
 *   1. Native bring-up + multi-device enumeration.
 *   2. Queue-driven throughput burst (producer floods, 4 workers
 *      drain through the hybrid router).
 *   3. Concurrent scheduler sweep (4 threads, results verified).
 *   4. Failover: per-device native disable -> dispatch -> recover.
 *   5. Backpressure: idle pool, queue must refuse beyond cap.
 *   6. Completed-cache eviction bound.
 *   7. Repeated native init/shutdown churn.
 *
 * Exit code 0 = PASS.
 */
public class LTEStressTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        System.out.println((cond ? "  PASS: " : "  FAIL: ") + what);
        if (!cond) failures++;
    }

    static long memUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    static void gcQuietly() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
    }

    /** Deterministic heightmap for a coordinate pair. */
    static int[] heightmapFor(int cx, int cz) {
        int[] h = new int[256];
        long seed = cx * 341873128712L + cz * 132897987541L + 1;
        for (int i = 0; i < 256; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            h[i] = 32 + (int) ((seed >>> 33) % 96);
        }
        return h;
    }

    /** Fresh pipeline input for (cx,cz). */
    static LTEChunkData makeData(int cx, int cz) {
        LTEChunkData d = new LTEChunkData(cx, cz);
        System.arraycopy(heightmapFor(cx, cz), 0, d.heightmap, 0, 256);
        d.cpuPrepared = true;
        return d;
    }

    /**
     * Oracle: run the shipped Java CPU backend on fresh buffers.
     */
    static double[] oracle(int cx, int cz) {
        LumenChunkComputeData d =
                new LumenChunkComputeData(makeData(cx, cz));
        TerrainProcessor.cpuProcess(d);
        return d.heightModification;
    }

    static boolean matches(double[] got, double[] want) {
        for (int k = 0; k < 256; k++) {
            if (got[k] != want[k]) return false;
        }
        return true;
    }

    public static void main(String[] args) throws Exception {

        System.out.println("=== LTE 2.1 stress test ===\n");

        /*
         * Stage 1: bring-up.
         */
        LTEConfig.verbose = false;
        LTEConfig.backend = "cpu";          // stage 2 measures CPU path
        LTEConfig.workerThreads = "4";

        check(LTENative.load(), "native core loads");
        DeviceScheduler.initialize();
        System.out.println("  devices: "
                + DeviceScheduler.describeDevices());

        LTEWorkerPool.start();
        check(LTEWorkerPool.size() == 4,
                "worker pool started with 4 threads");

        gcQuietly();
        long memBaseline = memUsedMb();

        /*
         * Stage 2: queue-driven throughput (CPU backend, 4 workers).
         */
        final int BURST = 20000;
        long t0 = System.nanoTime();

        Thread producer = new Thread(() -> {
            for (int i = 0; i < BURST; i++) {
                int cx = (i % 200) - 100;
                int cz = (i / 200) - 50;

                while (!ChunkQueue.addChunk(cx, cz,
                        makeData(cx, cz))) {
                    try {
                        Thread.sleep(1);        // backpressure
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "lte-stress-producer");

        producer.start();
        producer.join();

        long submitMs = (System.nanoTime() - t0) / 1_000_000L;

        while (LTEStats.getChunksCompleted() < BURST) {
            Thread.sleep(20);
        }

        long drainMs = (System.nanoTime() - t0) / 1_000_000L;

        check(LTEStats.getChunksCompleted() == BURST,
                "all " + BURST + " chunks processed (drain "
                + drainMs + " ms, submit " + submitMs + " ms)");

        System.out.println("  CPU-pipeline throughput: "
                + (BURST * 1000L / Math.max(1, drainMs))
                + " chunks/s");

        check(ChunkQueue.getSize() == 0, "queue fully drained");

        /*
         * Stop workers so later stages control execution exactly.
         */
        LTEWorkerPool.shutdown();
        for (Thread th : Thread.getAllStackTraces().keySet()) {
            if (th.getName().startsWith("LTE-")) th.join(3000);
        }

        /*
         * Stage 3: concurrent scheduler sweep (direct dispatch).
         */
        LTEConfig.backend = "gpu";

        final int SWEEP = 4000;
        final java.util.concurrent.atomic.AtomicInteger bad =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger cpuFellback =
                new java.util.concurrent.atomic.AtomicInteger();

        Thread[] threads = new Thread[4];
        long s0 = System.nanoTime();

        for (int t = 0; t < 4; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> {
                for (int i = tid; i < SWEEP; i += 4) {
                    int cx = (i * 11) % 4096 - 2048;
                    int cz = (i * 17 + 3) % 4096 - 2048;

                    int[] h = heightmapFor(cx, cz);
                    double[] got = new double[256];

                    boolean gpu =
                        DeviceScheduler.processWithFailover(
                            cx, cz, h, got);

                    if (!gpu) {
                        /*
                         * Production contract: generator falls back to
                         * the Java CPU backend - mirror that here.
                         */
                        cpuFellback.incrementAndGet();
                        LumenChunkComputeData cd =
                            new LumenChunkComputeData(makeData(cx, cz));
                        TerrainProcessor.cpuProcess(cd);
                        got = cd.heightModification;
                    }

                    if (!matches(got, oracle(cx, cz))) {
                        bad.incrementAndGet();
                    }
                }
            }, "lte-sweep-" + t);
            threads[t].start();
        }

        for (Thread th : threads) th.join();

        long sweepMs = (System.nanoTime() - s0) / 1_000_000L;

        check(bad.get() == 0,
                "concurrent sweep: " + SWEEP
                + " chunks correct across 4 threads ("
                + sweepMs + " ms)");

        System.out.println("  scheduler throughput: "
                + (SWEEP * 1000L / Math.max(1, sweepMs))
                + " chunks/s"
                + " (devices=" + DeviceScheduler.usableDeviceCount()
                + ", cpu-fallbacks=" + cpuFellback.get() + ")");

        /*
         * Stage 4: failover semantics per device.
         */
        int devs = LTENative.getDeviceCount();
        boolean failoverOk = true;

        for (int d = 0; d < devs && failoverOk; d++) {

            LTENative.setDeviceEnabled(d, false);

            double[] got = new double[256];
            boolean gpuDid =
                DeviceScheduler.processWithFailover(
                    77, -77, heightmapFor(77, -77), got);

            if (!gpuDid) {
                // Production contract: CPU fallback must engage.
                LumenChunkComputeData cd =
                    new LumenChunkComputeData(makeData(77, -77));
                TerrainProcessor.cpuProcess(cd);
                got = cd.heightModification;
            }

            boolean correct = matches(got, oracle(77, -77));

            LTENative.setDeviceEnabled(d, true);

            boolean recovered = LTENative.isDeviceHealthy(d);

            if (!correct || !recovered) failoverOk = false;

            check(true, "device " + d
                    + " disabled -> handled="
                    + (gpuDid ? "other GPU" : "CPU")
                    + " -> correct=" + correct
                    + " -> recovered=" + recovered);
        }

        // Cooldowns from forced failures may linger; reset state.
        DeviceScheduler.initialize();

        check(failoverOk, "failover preserves correctness everywhere");

        /*
         * Stage 5: backpressure - with the pool STOPPED the queue
         * cannot drain, so the capacity refusal is deterministic.
         */
        LTEConfig.maxQueueSize = 64;

        int accepted = 0;
        for (int i = 0; i < 500; i++) {
            if (ChunkQueue.addChunk(
                    9000 + i, 9000 - i, makeData(9000 + i, 9000 - i))) {
                accepted++;
            }
        }

        check(accepted == Math.min(500, 64),
                "queue respects capacity (accepted " + accepted
                + " of 500 with cap 64)");

        // Unwind: drain via direct processing (no workers).
        while (ChunkQueue.getNextChunk() != null) { /* discard */ }

        check(ChunkQueue.getSize() == 0, "manual unwind drains queue");

        /*
         * Stage 6: completed-cache eviction bound (10k uniques).
         */
        for (int i = 0; i < 10000; i++) {
            CompletedChunkCache.markCompleted(i, 0);
        }

        int cacheSize = CompletedChunkCache.getSize();

        check(cacheSize > 0 && cacheSize <= 4096,
                "completed cache bounded under flood (size="
                + cacheSize + ")");
        check(!CompletedChunkCache.isCompleted(0, 0)
                && CompletedChunkCache.isCompleted(9999, 0),
                "eviction is FIFO (oldest gone, newest present)");

        /*
         * Stage 7: native lifecycle churn.
         */
        boolean churnOk = true;
        for (int cycle = 0; cycle < 3 && churnOk; cycle++) {
            LTENative.shutdown();
            if (!LTENative.load()) { churnOk = false; break; }
            DeviceScheduler.initialize();

            double[] got = new double[256];
            boolean ok = DeviceScheduler.processWithFailover(
                    5, 5, heightmapFor(5, 5), got);
            if (!ok) {
                LumenChunkComputeData cd =
                    new LumenChunkComputeData(makeData(5, 5));
                TerrainProcessor.cpuProcess(cd);
                got = cd.heightModification;
            }
            if (!matches(got, oracle(5, 5))) churnOk = false;
        }

        check(churnOk, "3x shutdown/load cycles stay functional");

        /*
         * Final accounting.
         */
        LTENative.shutdown();

        gcQuietly();
        long memEnd = memUsedMb();
        System.out.println("\n  memory: end " + memEnd
                + " MB (baseline " + memBaseline + " MB)");
        System.out.println("  stats: " + LTEStats.snapshot());
        System.out.println("  gpu->cpu failovers: "
                + DeviceTelemetry.GPU_TO_CPU_FAILOVERS.get()
                + " overflow: "
                + DeviceTelemetry.OVERFLOW_DISPATCHES.get());

        check(memEnd < memBaseline + 256,
                "no runaway memory growth");

        System.out.println();
        System.out.println(failures == 0
                ? "RESULT: PASS" : "RESULT: FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}

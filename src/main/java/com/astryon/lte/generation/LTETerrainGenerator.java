package com.astryon.lte.generation;

import com.astryon.lte.benchmark.LTEBenchmark;
import com.astryon.lte.chunk.ChunkQueue;
import com.astryon.lte.compute.LumenChunkComputeData;
import com.astryon.lte.core.Backend;
import com.astryon.lte.gpu.DeviceScheduler;
import com.astryon.lte.gpu.LTENative;
import com.astryon.lte.terrain.TerrainProcessor;

public class LTETerrainGenerator {

    private LTETerrainGenerator() {
    }

    /**
     * Main LTE terrain generation entry point.
     *
     * Backend selection happens here.
     *
     * CPU:
     *     TerrainProcessor.cpuProcess(...)
     *
     * GPU:
     *     DeviceScheduler -> per-device native OpenCL dispatch with
     *     automatic failover to other devices, then CPU.
     */
    public static void generate(
            LumenChunkComputeData data
    ) {

        /*
         * Backend resolution order:
         *   1. config/lte.properties "backend" explicit override
         *      ("gpu" / "cpu") - users can pin a backend.
         *   2. "auto": benchmark selection + hybrid queue-pressure
         *      routing (see below).
         */
        boolean gpuRequested =
                switch (com.astryon.lte.config.LTEConfig.backend) {
                    case "gpu" -> true;
                    case "cpu" -> false;
                    default -> LTEBenchmark.selectedBackend == Backend.GPU;
                };


        /*
         * Hybrid routing (LTE 2.1): a single synchronized GPU dispatch
         * costs ~30-60 us in transfer/sync overhead while the CPU path
         * computes this chunk's 256 columns in well under a
         * microsecond. When the queue is nearly drained, CPU is the
         * lower-latency choice; under backlog the GPUs' throughput
         * wins and they take over.
         *
         * Threshold: half of maxQueueSize (>= 8 chunks). Only applies
         * in "auto" mode - explicit backend config is honored exactly.
         */
        if (gpuRequested && com.astryon.lte.config.LTEConfig.backend.equals(
                com.astryon.lte.config.LTEConfig.BACKEND_AUTO)) {

            int pressure = ChunkQueue.getSize();

            int threshold =
                    Math.max(8,
                        com.astryon.lte.config.LTEConfig.maxQueueSize / 2);

            gpuRequested = pressure >= threshold;
        }


        boolean nativeReady =
                LTENative.isLoaded()
                && DeviceScheduler.isInitialized()
                && DeviceScheduler.usableDeviceCount() > 0;

        if (!nativeReady) {

            /*
             * Native core absent, no OpenCL device, or every device
             * failed its benchmark - config cannot conjure a GPU.
             */
            if (gpuRequested
                    && !"cpu".equals(
                        com.astryon.lte.config.LTEConfig.backend)) {

                System.out.println(
                        "[LTE] GPU requested but no usable device "
                        + "- using CPU");
            }

            gpuRequested = false;
        }


        long start = System.nanoTime();

        boolean gpuUsed = false;


        if (gpuRequested) {

            gpuUsed = generateGPU(data);

        } else {

            generateCPU(data);

        }


        long elapsedNs = System.nanoTime() - start;


        /*
         * Feed monitoring with real per-chunk timings (lock-free).
         */
        com.astryon.lte.monitor.LTEStats.chunkCompleted(elapsedNs);

        com.astryon.lte.monitor.LTEStats.recordBackend(gpuUsed);

    }


    /**
     * CPU terrain backend.
     */
    private static void generateCPU(
            LumenChunkComputeData data
    ) {

        TerrainProcessor.cpuProcess(data);

    }


    /**
     * GPU terrain backend.
     *
     * Dispatches to one healthy device via the scheduler; on failure
     * the scheduler has already rotated through every usable device,
     * so this falls back to CPU directly and quietly. A pinned device
     * (config gpuDevice=N) is tried exclusively while it works.
     *
     * @return true when a GPU produced the output, false on CPU path
     */
    private static boolean generateGPU(
            LumenChunkComputeData data
    ) {

        try {

            /*
             * Write straight into the chunk's output buffer - no
             * temporary allocation, no second copy.
             */
            boolean gpuDone =
                    DeviceScheduler.processWithFailover(
                            data.chunkX,
                            data.chunkZ,
                            data.heightmap,
                            data.heightModification
                    );

            if (!gpuDone) {
                TerrainProcessor.cpuProcess(data);
                return false;
            }

            data.markGPUComplete();
            return true;

        } catch (Throwable e) {

            /*
             * The native/JNI layer threw something unexpected - never
             * let it reach the server's world-gen thread.
             */
            System.out.println(
                    "[LTE] GPU dispatch error (falling back to CPU): "
                            + e.getMessage()
            );

            TerrainProcessor.cpuProcess(data);
            return false;
        }
    }

}

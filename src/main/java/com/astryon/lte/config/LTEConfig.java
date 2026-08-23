package com.astryon.lte.config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/*
 * LTE runtime configuration (config/lte.properties).
 *
 * Defaults keep the engine conservative; the benchmark decides the
 * backend unless gpuEnabled is explicitly overridden here.
 */
public class LTEConfig {

    /** Sentinel for benchmark-driven backend selection. */
    public static final String BACKEND_AUTO = "auto";

    /** Force backend selection: "auto" (benchmark), "gpu", "cpu". */
    public static String backend = BACKEND_AUTO;

    /** Hard cap on queued chunks. */
    public static int maxQueueSize = 512;

    /**
     * Verbose pipeline logging. Default false in 2.1: the default
     * server log stays lightweight; periodic status lines are
     * activity-gated and verbose-only.
     */
    public static boolean verbose = false;

    /**
     * ------------------------------------------------------------------
     * LTE 2.1 multi-GPU scheduling
     * ------------------------------------------------------------------
     */

    /**
     * "auto" (benchmark-weighted rotation across all healthy devices)
     * or a specific device index ("0", "1", ...) to pin all GPU work
     * to one device.
     */
    public static String gpuDevice = "auto";

    /**
     * When false, devices whose benchmark score is far below the best
     * device's are excluded from rotation instead of merely getting
     * fewer chunks. Default true: every working GPU participates.
     */
    public static boolean allowSlowDevices = true;

    /**
     * Number of LTE worker threads draining the chunk queue.
     * "auto" resolves to min(4, max(1, cores / 4)) - enough parallelism
     * to keep every GPU busy and use spare CPU cores without stealing
     * significant time from the Minecraft server itself.
     */
    public static String workerThreads = "auto";

    /** Resolved worker count (set once at startup). */
    public static int resolvedWorkerThreads = 1;

    private static final File FILE =
            new File("config", "lte.properties");


    public static void load() {

        Properties props = new Properties();


        if (FILE.exists()) {

            try (var in = Files.newInputStream(FILE.toPath())) {

                props.load(in);

                backend = props.getProperty(
                    "backend", backend).trim().toLowerCase();

                maxQueueSize = parseIntSafe(
                    props.getProperty("maxQueueSize"),
                    maxQueueSize);

                verbose = Boolean.parseBoolean(
                    props.getProperty("verbose",
                        String.valueOf(verbose)));

                gpuDevice = props.getProperty(
                    "gpuDevice", gpuDevice).trim().toLowerCase();

                allowSlowDevices = Boolean.parseBoolean(
                    props.getProperty("allowSlowDevices",
                        String.valueOf(allowSlowDevices)));

                workerThreads = props.getProperty(
                    "workerThreads", workerThreads).trim().toLowerCase();

                System.out.println("[LTE] Config loaded");

            } catch (IOException e) {

                System.out.println(
                    "[LTE] Config read failed, using defaults: "
                    + e.getMessage());

            }

        } else {

            saveDefaults();
        }
    }


    private static int parseIntSafe(String value, int fallback) {

        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }


    private static void saveDefaults() {

        try {

            FILE.getParentFile().mkdirs();

            try (Writer out =
                     new FileWriter(FILE)) {

                out.write("# Lumen Terrain Engine configuration\n");
                out.write("# backend = auto | gpu | cpu\n");
                out.write("backend=" + backend + "\n");
                out.write("# gpuDevice = auto | <device index>\n");
                out.write("gpuDevice=" + gpuDevice + "\n");
                out.write("# allow slow GPUs to participate (2.1 multi-GPU)\n");
                out.write("allowSlowDevices=" + allowSlowDevices + "\n");
                out.write("# LTE worker threads: auto | <count>\n");
                out.write("workerThreads=" + workerThreads + "\n");
                out.write("maxQueueSize=" + maxQueueSize + "\n");
                out.write("verbose=" + verbose + "\n");
            }

            System.out.println("[LTE] Default config written");

        } catch (IOException e) {

            System.out.println(
                "[LTE] Config write failed: " + e.getMessage());
        }
    }
}

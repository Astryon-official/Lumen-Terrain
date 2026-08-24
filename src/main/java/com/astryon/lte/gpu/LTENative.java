package com.astryon.lte.gpu;

import com.astryon.lte.platform.NativeLoader;

/*
 * JNI bridge to the LTE native core.
 *
 * Library resolution is fully cross-platform and handled by
 * NativeLoader: OS + arch detection -> bundled binary selection ->
 * safe extraction -> absolute-path load -> validated initialization.
 *
 * Failure contract: every method returns a safe value (null / false /
 * empty string / 0) instead of throwing, so the Java pipeline can
 * always fall back to the CPU backend. A missing binary for the
 * current platform is an expected condition, not an error.
 */
public final class LTENative {

    private static boolean ready = false;

    /**
     * Loads and initializes the native core through the platform
     * resolver. Idempotent; never throws.
     *
     * @return true when the native core is ready for use.
     */
    public static synchronized boolean load() {

        if (ready) {
            return true;   // already up - true idempotence
        }

        /*
         * After a shutdown() the JVM-level library stays resident;
         * calling System.load again would throw UnsatisfiedLinkError
         * (already loaded in this classloader). So: reset the one-shot
         * latch and let loadAndInitialize re-run the native
         * initializer over the resident library.
         */
        NativeLoader.resetForRelink();

        ready = NativeLoader.loadAndInitialize(
            new NativeLoader.NativeHandle() {

            @Override
            public String version() {
                return "2.0.0";
            }

            @Override
            public void initialize() {
                initializeNative();
            }
        });

        return ready;
    }

    /** True after successful load + validated initialization. */
    public static boolean isLoaded() {
        return ready;
    }

    // ------------------------------------------------------------------
    // Native API (each maps 1:1 onto an exported JNI function)
    // ------------------------------------------------------------------

    private static native void initializeNative();

    private static native void shutdownNative();

    /**
     * Shuts down the native core and releases OpenCL resources.
     * Clears the ready latch so a later load() genuinely
     * re-initializes instead of short-circuiting (regression:
     * churn test found GPU-less restarts).
     */
    public static synchronized void shutdown() {
        try {
            shutdownNative();
        } finally {
            ready = false;
        }
    }

    /** True when a usable OpenCL device was found and initialized. */
    public static native boolean isGPURuntimeAvailable();

    /** Device name of the selected OpenCL device, "" if unavailable. */
    public static native String getGPUDeviceName();

    /** Native core version string, e.g. "2.0.0". */
    public static native String getNativeVersion();

    /**
     * ------------------------------------------------------------------
     * LTE 2.1 multi-device API
     * ------------------------------------------------------------------
     */

    /**
     * Number of usable OpenCL devices (all platforms, non-CPU).
     * 0 when the native core is absent or no device is usable.
     */
    public static native int getDeviceCount();

    /**
     * Device info for every enumerated device.
     *
     * Returns String[count][6]:
     *   [i][0] name
     *   [i][1] vendor
     *   [i][2] platform name
     *   [i][3] driver version
     *   [i][4] compute units (string)
     *   [i][5] global memory MB (string)
     *
     * Empty array (never null) when no devices exist.
     */
    public static native String[][] getDeviceInfo();

    /**
     * True when the device can accept work right now. Unhealthy or
     * disabled devices report false; failed devices self-heal after a
     * native-side cooldown (half-open retry).
     */
    public static native boolean isDeviceHealthy(int index);

    /**
     * Config escape hatch: manually disable/enable a device.
     */
    public static native void setDeviceEnabled(int index, boolean enabled);

    /**
     * Per-device benchmark with the identical terrain workload used by
     * runCPUBenchmark, so scores are directly comparable.
     * Returns chunks/second, or 0 when this device cannot be used.
     */
    public static native long benchmarkDevice(int index);

    /**
     * Process a chunk on a SPECIFIC device.
     *
     * Returns null on any failure - the scheduler then retries on
     * another device or falls back to CPU. Never throws.
     */
    public static native double[] gpuProcessTerrain(
            int x,
            int z,
            int deviceIndex,
            int[] heightmap
    );

    /**
     * Aggregated GPU benchmark: sum of all healthy devices' scores.
     * Kept for the hardware profile cache; per-device weighting uses
     * {@link #benchmarkDevice}.
     */
    public static native long runGPUBenchmark();

    /**
     * Reference CPU implementation of the identical terrain workload,
     * so CPU/GPU scores are directly comparable.
     */
    public static native long runCPUBenchmark();

}

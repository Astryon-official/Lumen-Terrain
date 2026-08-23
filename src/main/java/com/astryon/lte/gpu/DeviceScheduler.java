package com.astryon.lte.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.astryon.lte.monitor.DeviceTelemetry;

/*
 * Multi-GPU work scheduler (LTE 2.1).
 *
 * Owns the device table and all dispatch decisions:
 *   - Round-robin across healthy devices (fair utilization).
 *   - Weighted by per-device benchmark score when scores differ
 *     significantly (>= 25% gap), so an RTX 2050 + Intel iGPU pair
 *     doesn't send 50% of chunks to the iGPU.
 *   - Automatic failover: null from one device -> try next -> CPU.
 *   - Health tracking with cooldown so a broken device is retried at
 *     most once per cooldown window instead of every chunk.
 *
 * Thread-safety contract:
 *   One thread owns one device AT A TIME via claimDevice(). Two
 *   threads never share a native queue concurrently.
 */
public final class DeviceScheduler {

    private DeviceScheduler() {
    }

    /** Retry cadence for unhealthy devices, milliseconds. */
    private static final long COOLDOWN_MS = 5000L;

    /**
     * If the fastest device beats another by this factor or more,
     * the slower one gets proportionally less work instead of an
     * equal share.
     */
    private static final double WEIGHT_GAP = 1.25;

    private static final class Slot {
        final int index;
        final String name;

        /** Volatile: read on hot path without locking. */
        volatile boolean usable = true;

        /** Nanotime of the earliest retry for an unusable device. */
        volatile long retryAtNanos = 0L;

        /**
         * Busy flag: a device processes one chunk at a time (one host
         * thread owns its OpenCL queue). Acquire/release with CAS.
         */
        final java.util.concurrent.atomic.AtomicBoolean busy =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Slot(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    private static volatile Slot[] slots = new Slot[0];
    private static volatile long[] weights = new long[0];

    /**
     * When config gpuDevice=N is set, all GPU work goes to device N
     * only (-1 = auto rotation across healthy devices).
     */
    private static volatile int pinnedDevice = -1;

    /**
     * False when allowSlowDevices=false: devices far below the best
     * score are excluded from rotation instead of getting fewer chunks.
     */
    private static volatile boolean weightedRotation = true;

    /**
     * Round-robin cursor. fetch-and-add keeps concurrent claims fair:
     * each thread scans from a different offset, no lock needed.
     */
    private static final AtomicInteger CURSOR = new AtomicInteger(0);

    /** Nanotime of the most recent dispatch (any device). */
    public static final AtomicLong LAST_ACTIVITY_NS = new AtomicLong();

    /** Minimum interval between periodic status lines, milliseconds. */
    private static final long STATUS_INTERVAL_MS = 60_000L;

    /** Last time the activity-gated status line was emitted. */
    private static volatile long lastStatusMs = 0L;

    private static volatile boolean initialized = false;

    /**
     * Builds the device table after LTENative.load(). Safe to call
     * repeatedly; re-runs re-enumerate (driver hotplug scenario).
     */
    public static synchronized void initialize() {

        if (!LTENative.isLoaded() || !LTENative.isGPURuntimeAvailable()) {
            slots = new Slot[0];
            weights = new long[0];
            initialized = true;
            System.out.println("[LTE] No OpenCL devices - CPU-only mode");
            return;
        }

        int count = LTENative.getDeviceCount();
        List<Slot> list = new ArrayList<>(count);
        long[] w = new long[count];

        for (int i = 0; i < count; i++) {

            String name = "device " + i;
            try {
                String[][] info = LTENative.getDeviceInfo();
                if (info != null && i < info.length && info[i] != null) {
                    name = info[i][0];
                }
            } catch (Throwable ignored) {
                // name stays generic; never fail init over cosmetics
            }

            long score = 0;
            try {
                score = LTENative.benchmarkDevice(i);
            } catch (Throwable t) {
                System.out.println(
                        "[LTE] Benchmark failed for " + name
                                + " - excluded from GPU scheduling");
                list.add(new Slot(i, name));
                w[i] = 0;
                continue;
            }

            if (score <= 0) {
                /*
                 * Cannot process terrain at all - NOT schedulable.
                 * Excluded from the slot table entirely so no pass
                 * can ever send it work (its weight would be 0 and
                 * usable=true would otherwise admit overflow).
                 */
                System.out.println(
                        "[LTE] Device not usable - excluded: " + name);
                continue;
            }

            Slot s = new Slot(i, name);
            list.add(s);
            w[i] = score;

            System.out.println("[LTE] GPU ready: " + name
                    + " (" + score + " chunks/s)");
        }

        slots = list.toArray(new Slot[0]);
        weights = w;

        DeviceTelemetry.ensureSize(slots.length);

        /*
         * Config-driven scheduling policy.
         */
        String pin = com.astryon.lte.config.LTEConfig.gpuDevice;
        pinnedDevice = -1;

        if (pin != null && !pin.isBlank() && !"auto".equalsIgnoreCase(pin)) {
            try {
                int idx = Integer.parseInt(pin.trim());
                if (idx >= 0 && idx < slots.length) {
                    pinnedDevice = slots[idx].index;
                    System.out.println("[LTE] GPU work pinned to device " + idx);
                } else {
                    System.out.println("[LTE] gpuDevice=" + pin
                            + " out of range - rotating across all devices");
                }
            } catch (NumberFormatException e) {
                System.out.println("[LTE] Invalid gpuDevice='" + pin
                        + "' - rotating across all devices");
            }
        }

        weightedRotation =
                com.astryon.lte.config.LTEConfig.allowSlowDevices;

        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Number of schedulable devices (may be 0). */
    public static int usableDeviceCount() {
        Slot[] snapshot = slots;
        int n = 0;
        for (Slot s : snapshot) {
            if (s.usable) {
                n++;
            }
        }
        return n;
    }

    /**
     * Reports a completed (or failed) dispatch. Called from
     * dispatchTo after each attempt.
     */
    public static void report(int deviceIndex, boolean success) {
        Slot[] snapshot = slots;
        for (Slot s : snapshot) {
            if (s.index == deviceIndex) {
                if (!success) {
                    s.usable = false;
                    s.retryAtNanos =
                            System.nanoTime() + COOLDOWN_MS * 1_000_000L;
                } else {
                    s.usable = true;
                }
                return;
            }
        }
    }

    /**
     * Full pipeline execution with automatic failover:
     * top-tier devices -> slower devices -> CPU fallback.
     *
     * Dispatch order:
     *   Pass 1: usable devices within the performance tier, tried
     *           heaviest-weight-first (round-robin among equals).
     *   Pass 2: remaining usable devices regardless of tier - only
     *           reached when every top-tier attempt failed, or when
     *           allowSlowDevices=false, never.
     *
     * Returns true when a GPU produced the result, false when CPU did.
     */
    public static boolean processWithFailover(
            int x, int z, int[] heightmap,
            double[] output
    ) {
        Slot[] snapshot = slots;
        int n = snapshot.length;

        if (n == 0 || !initialized) {
            return false;
        }

        /*
         * Pinned mode: exactly one device, still honoring the busy
         * flag so two workers cannot share its native queue.
         */
        int pin = pinnedDevice;
        if (pin >= 0) {
            Slot pinned = null;
            for (Slot cand : snapshot) {
                if (cand.index == pin) {
                    pinned = cand;
                    break;
                }
            }
            if (pinned == null) {
                return false;
            }
            long t = System.nanoTime();
            if (!tryAcquire(pinned, t)) {
                DeviceTelemetry.GPU_TO_CPU_FAILOVERS.incrementAndGet();
                return false;
            }
            boolean ok = dispatchTo(pinned, x, z, heightmap, output);
            pinned.busy.set(false);
            if (!ok) {
                DeviceTelemetry.GPU_TO_CPU_FAILOVERS.incrementAndGet();
            }
            return ok;
        }

        long best = 0;
        for (long v : weights) {
            if (v > best)
                best = v;
        }
        final long tierThreshold =
                (long) (best / WEIGHT_GAP);

        /*
         * Candidates sorted heaviest-first; ties broken by rotation so
         * equal devices share load over time.
         */
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final long rotSeed =
                Math.floorMod(CURSOR.getAndIncrement(), n);

        java.util.Arrays.sort(order, (a, b) -> {
            long wa = weights[snapshot[a].index];
            long wb = weights[snapshot[b].index];
            if (wa != wb) {
                return Long.compare(wb, wa);   // heavier first
            }
            return Long.compare(
                    Math.floorMod(a - rotSeed, n),
                    Math.floorMod(b - rotSeed, n));
        });

        long now = System.nanoTime();

        // Pass 1: top tier.
        for (Integer idx : order) {
            Slot s = snapshot[idx];
            if (!tryAcquire(s, now)) {
                continue;
            }
            boolean ok = false;
            if (weights[s.index] >= tierThreshold) {
                ok = dispatchTo(s, x, z, heightmap, output);
            }
            s.busy.set(false);
            if (ok) {
                return true;
            }
            now = System.nanoTime();   // cooldown may have been set
        }

        // Pass 2: slower devices as overflow/failover.
        if (!weightedRotation) {
            DeviceTelemetry.GPU_TO_CPU_FAILOVERS.incrementAndGet();
            return false;
        }

        for (Integer idx : order) {
            Slot s = snapshot[idx];
            if (!tryAcquire(s, now)) {
                continue;
            }
            boolean ok = false;
            if (weights[s.index] < tierThreshold) {
                ok = dispatchTo(s, x, z, heightmap, output);
            }
            s.busy.set(false);
            if (ok) {
                DeviceTelemetry.OVERFLOW_DISPATCHES.incrementAndGet();
                return true;
            }
            now = System.nanoTime();
        }

        /*
         * Every usable GPU failed this chunk - CPU takes it.
         */
        DeviceTelemetry.OVERFLOW_DISPATCHES.incrementAndGet();
        DeviceTelemetry.GPU_TO_CPU_FAILOVERS.incrementAndGet();
        LAST_ACTIVITY_NS.set(System.nanoTime());

        return false;
    }

    /**
     * A slot can be attempted when usable (or probing) AND not
     * currently owned by another worker thread.
     * On acquire, marks the slot busy.
     */
    private static boolean tryAcquire(Slot s, long nowNanos) {
        if (!s.usable && nowNanos < s.retryAtNanos) {
            return false;   // cooling down
        }
        return s.busy.compareAndSet(false, true);
    }

    /**
     * Single-device dispatch with health reporting. Never throws.
     * Caller owns s.busy around this call.
     */
    private static boolean dispatchTo(
            Slot s,
            int x, int z,
            int[] heightmap,
            double[] output
    ) {
        int deviceIndex = s.index;

        try {
            double[] result = LTENative.gpuProcessTerrain(
                    x, z, deviceIndex, heightmap);

            if (result != null && result.length == 256) {
                System.arraycopy(result, 0, output, 0, 256);
                report(deviceIndex, true);
                DeviceTelemetry.recordDispatch(slotIndexOf(s), true);
                LAST_ACTIVITY_NS.set(System.nanoTime());
                maybeLogStatus();
                return true;
            }

        } catch (Throwable t) {
            // handled below
            // fall through to failure reporting
        }

        report(deviceIndex, false);

        DeviceTelemetry.recordDispatch(slotIndexOf(s), false);
        System.out.println("[LTE] GPU dispatch failed on device "
                + deviceIndex + " - cooling down "
                + (COOLDOWN_MS / 1000) + "s");

        return false;
    }

    private static int slotIndexOf(Slot s) {
        Slot[] snapshot = slots;
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] == s) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Emits a multi-line scheduler/telemetry status at most once per
     * STATUS_INTERVAL_MS, and only while work is actually flowing.
     * Called from the dispatch hot path; the common case is two
     * volatile reads.
     */
    private static void maybeLogStatus() {
        long nowMs = System.nanoTime() / 1_000_000L;

        if (nowMs - lastStatusMs < STATUS_INTERVAL_MS) {
            return;
        }

        lastStatusMs = nowMs;

        if (!com.astryon.lte.config.LTEConfig.verbose) {
            return;   // quiet mode: only activity-gated lines exist
        }

        List<String> names = new ArrayList<>();
        List<Long> scores = new ArrayList<>();
        List<Boolean> usable = new ArrayList<>();

        for (Slot s : slots) {
            names.add(s.name);
            scores.add(weights[s.index]);
            usable.add(s.usable);
        }

        System.out.println(DeviceTelemetry.snapshot(
                names, scores, usable));
    }

    /**
     * Diagnostics string for /lte status style output.
     */
    public static String describeDevices() {
        Slot[] snapshot = slots;
        if (snapshot.length == 0) {
            return "no devices";
        }

        StringBuilder b = new StringBuilder();
        for (Slot s : snapshot) {
            if (b.length() > 0)
                b.append(", ");
            b.append(s.name);
            if (!s.usable) {
                b.append(" [unhealthy]");
            }
        }
        return b.toString();
    }

    /** True when the device can accept work right now. */
    public static boolean isDeviceUsable(int slot) {
        Slot[] snapshot = slots;
        if (slot < 0 || slot >= snapshot.length) {
            return false;
        }
        Slot s = snapshot[slot];
        return s.usable || System.nanoTime() >= s.retryAtNanos;
    }

    /** Measured benchmark score for the slot, 0 when unknown. */
    public static long deviceScore(int slot) {
        Slot[] snapshot = slots;
        if (slot < 0 || slot >= snapshot.length) {
            return 0;
        }
        return weights[snapshot[slot].index];
    }

    /** Human-readable name of the slot's device. */
    public static String deviceName(int slot) {
        Slot[] snapshot = slots;
        if (slot < 0 || slot >= snapshot.length) {
            return "?";
        }
        return snapshot[slot].name;
    }
}

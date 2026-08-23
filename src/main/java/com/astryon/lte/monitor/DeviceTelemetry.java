package com.astryon.lte.monitor;

import java.util.concurrent.atomic.AtomicLong;

/*
 * Per-device telemetry for the GPU scheduler (LTE 2.1).
 *
 * Hot path cost: two relaxed AtomicLong adds per chunk dispatch
 * (success/failure). No locks, no allocation, no strings.
 *
 * All reads happen on slow paths (status logging / diagnostics).
 */
public final class DeviceTelemetry {

    private DeviceTelemetry() {
    }

    /** Indexed by scheduler slot position, not native device index. */
    private static volatile AtomicLong[] dispatched = new AtomicLong[0];
    private static volatile AtomicLong[] failed = new AtomicLong[0];

    /** Times pass-2 (overflow/slower tier) was needed. */
    public static final AtomicLong OVERFLOW_DISPATCHES =
            new AtomicLong();

    /** Times CPU handled a chunk after every usable GPU failed. */
    public static final AtomicLong GPU_TO_CPU_FAILOVERS =
            new AtomicLong();

    /** Times a half-open cooldown probe was attempted. */
    public static final AtomicLong COOLDOWN_PROBES =
            new AtomicLong();

    /**
     * Resizes the tables (called once from scheduler init).
     * Existing counters are preserved on re-enumeration.
     */
    public static synchronized void ensureSize(int slots) {
        AtomicLong[] d = dispatched;
        AtomicLong[] f = failed;

        if (d.length >= slots) {
            return;
        }

        AtomicLong[] nd = new AtomicLong[slots];
        AtomicLong[] nf = new AtomicLong[slots];

        for (int i = 0; i < slots; i++) {
            nd[i] = i < d.length ? d[i] : new AtomicLong();
            nf[i] = i < f.length ? f[i] : new AtomicLong();
        }

        dispatched = nd;
        failed = nf;
    }

    public static void recordDispatch(int slot, boolean success) {
        AtomicLong[] d = dispatched;
        AtomicLong[] f = failed;

        if (slot < 0) {
            return;
        }

        if (success) {
            if (slot < d.length && d[slot] != null) {
                d[slot].incrementAndGet();
            }
        } else {
            if (slot < f.length && f[slot] != null) {
                f[slot].incrementAndGet();
            }
        }
    }

    /**
     * Multi-line human-readable snapshot for periodic status output.
     */
    public static String snapshot(
            Iterable<String> slotDescriptions,
            Iterable<Long> slotScores,
            Iterable<Boolean> slotUsable
    ) {
        StringBuilder b = new StringBuilder("[LTE] status:");
        int i = 0;

        java.util.Iterator<String> ni = slotDescriptions.iterator();
        java.util.Iterator<Long> si = slotScores.iterator();
        java.util.Iterator<Boolean> ui = slotUsable.iterator();

        while (ni.hasNext()) {
            String name = ni.next();
            long score = si.hasNext() ? si.next() : 0;
            boolean usable = ui.hasNext() && ui.next();
            long disp = i < dispatched.length && dispatched[i] != null
                    ? dispatched[i].get() : 0;
            long fail = i < failed.length && failed[i] != null
                    ? failed[i].get() : 0;

            b.append("\n  dev").append(i).append(": ").append(name)
             .append(" score=").append(score)
             .append(usable ? " [up]" : " [DOWN]")
             .append(" ok=").append(disp)
             .append(" err=").append(fail);

            i++;
        }

        b.append("\n  overflow=").append(OVERFLOW_DISPATCHES.get())
         .append(" gpu->cpu=").append(GPU_TO_CPU_FAILOVERS.get());

        return b.toString();
    }

    /** Resets all tables (shutdown/re-init hygiene). */
    public static synchronized void reset() {
        dispatched = new AtomicLong[0];
        failed = new AtomicLong[0];
    }
}

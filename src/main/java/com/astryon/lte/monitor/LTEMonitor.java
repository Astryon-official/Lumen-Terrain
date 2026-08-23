package com.astryon.lte.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/*
 * Optional performance log (logs/lumen-terrain-engine/performance.log).
 *
 * Never called automatically: only diagnostics commands or explicit
 * verbose sessions should invoke it. Appends one line per call using
 * NIO; failures are swallowed - monitoring must never break the game.
 */
public final class LTEMonitor {

    private static final Path LOG_FILE =
            Path.of("logs", "lumen-terrain-engine",
                    "performance.log");

    private LTEMonitor() {
    }

    public static void writeStats(int queueSize) {

        try {

            Files.createDirectories(LOG_FILE.getParent());

            String line = "[LTE Stats] "
                    + "Chunks: " + LTEStats.getChunksCompleted()
                    + " | Queue: " + queueSize
                    + " | Avg: "
                    + LTEStats.getAverageTimeNanos() / 1_000_000.0
                    + "ms"
                    + " | GPU: " + LTEStats.GPU_CHUNKS.get()
                    + " | CPU: " + LTEStats.CPU_CHUNKS.get()
                    + System.lineSeparator();

            Files.writeString(
                LOG_FILE,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        } catch (IOException e) {

            // Monitoring must never break the server.
            System.out.println(
                "[LTE] Could not write performance log: "
                + e.getMessage());
        }
    }
}

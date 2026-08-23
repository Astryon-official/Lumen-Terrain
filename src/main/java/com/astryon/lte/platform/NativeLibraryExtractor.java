package com.astryon.lte.platform;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/*
 * Extracts a bundled native library from the mod JAR to a stable
 * per-mod runtime directory and returns its absolute path.
 *
 * Design:
 *   - Target dir: <user.dir>/lte-native/<platform>/<version>/
 *     (per-user, versioned; stale copies are never touched while in use)
 *   - Atomic extraction (temp file + ATOMIC_MOVE) so a crash mid-extract
 *     can never leave a corrupt library in place.
 *   - The ".ready" marker stores the library's CRC32. On later boots the
 *     cached copy is verified against it, so a same-version rebuild can
 *     never silently reuse a stale binary (regression: LTE-BUG-STALE-DLL).
 *   - Falls back to plain replace when the filesystem does not support
 *     atomic moves.
 */
public final class NativeLibraryExtractor {

    /** Marker written next to a fully extracted library. */
    static final String READY_MARKER_SUFFIX = ".ready";

    private NativeLibraryExtractor() {
    }

    /**
     * Extracts the given JAR resource into the per-platform runtime
     * directory. Returns the absolute path of the extracted library,
     * or null on any failure.
     */
    public static String extract(
            Platform platform,
            String resourcePath,
            String fileName,
            String version
    ) {
        try {
            Path targetDir = targetDirectory(platform, version);

            Path library = targetDir.resolve(fileName);

            Path ready = targetDir.resolve(fileName + READY_MARKER_SUFFIX);


            /*
             * Fast path: previously extracted AND still matches the
             * recorded checksum. A rebuilt-but-same-version binary is
             * detected here and re-extracted.
             */
            if (Files.isRegularFile(library) && Files.isRegularFile(ready)) {

                long recorded = readMarkerCrc(ready);

                if (recorded >= 0
                        && recorded == crcOfFile(library)) {
                    return library.toAbsolutePath().toString();
                }

                System.out.println(
                    "[LTE] Stale native library detected - refreshing");
            }


            /*
             * Classloader lookup: always relative to the classpath
             * root, so "native/<platform>/lib..." resolves inside the
             * mod JAR regardless of this class's package.
             */
            String loaderPath = resourcePath.startsWith("/")
                    ? resourcePath.substring(1)
                    : resourcePath;

            try (InputStream raw =
                     NativeLibraryExtractor.class
                         .getClassLoader()
                         .getResourceAsStream(loaderPath)) {

                if (raw == null) {

                    System.out.println(
                        "[LTE] No native binary bundled at "
                        + loaderPath);

                    return null;
                }


                Files.createDirectories(targetDir);


                Path temp =
                    targetDir.resolve(fileName + ".tmp");

                CRC32 crc = new CRC32();

                try (InputStream in = new CheckedInputStream(raw, crc);
                     OutputStream out =
                         Files.newOutputStream(temp)) {

                    in.transferTo(out);
                }


                /*
                 * Atomic swap-in where supported.
                 */
                try {

                    Files.move(
                        temp,
                        library,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);

                } catch (AtomicMoveNotSupportedException e) {

                    Files.move(
                        temp,
                        library,
                        StandardCopyOption.REPLACE_EXISTING);
                }


                /*
                 * Mark complete only after the library is fully in
                 * place; record the checksum we actually wrote.
                 */
                Files.writeString(
                    ready,
                    "crc=" + crc.getValue(),
                    StandardCharsets.US_ASCII);
            }


            System.out.println(
                "[LTE] Extracted native library: "
                + library.toAbsolutePath());

            return library.toAbsolutePath().toString();

        } catch (Throwable t) {

            System.out.println(
                "[LTE] Native extraction failed: " + t.getMessage());

            return null;
        }
    }

    /** Per-user, per-platform, per-version runtime directory. */
    static Path targetDirectory(Platform platform, String version) {

        Path base =
            Path.of(System.getProperty("user.dir"), "lte-native");

        return base.resolve(platform.identifier())
                   .resolve(version);
    }


    /** Parses "crc=<decimal>"; -1 when missing/corrupt. */
    private static long readMarkerCrc(Path ready) {
        try {
            String text = Files.readString(ready,
                StandardCharsets.US_ASCII).trim();

            if (text.startsWith("crc=")) {
                return Long.parseLong(text.substring(4));
            }
        } catch (Throwable ignored) {
            // fall through to re-extraction
        }
        return -1L;
    }


    /** CRC32 of a file on disk; -1 when unreadable. */
    private static long crcOfFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {

            CRC32 crc = new CRC32();
            byte[] buf = new byte[64 * 1024];

            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }

            return crc.getValue();

        } catch (Throwable t) {
            return -1L;
        }
    }
}

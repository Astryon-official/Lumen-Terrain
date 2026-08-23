package com.astryon.lte.platform;

/*
 * Platform/architecture resolver.
 *
 * Resolution chain:
 *   1. Detect OS + architecture (Java properties only).
 *   2. Select the matching bundled native binary from the JAR.
 *   3. Extract it safely to a per-user runtime directory.
 *   4. Load it with an explicit absolute path.
 *   5. Validate native initialization succeeded.
 *
 * Any failure at any step disables the native backend gracefully -
 * the mod keeps running with pure-Java CPU processing and vanilla
 * world generation. This class never throws.
 */
public final class NativeLoader {

    private static volatile boolean loadAttempted = false;

    private static volatile boolean loaded = false;

    /**
     * Set once System.load has succeeded at least once in this JVM;
     * the library cannot (and must not) be loaded a second time.
     */
    private static volatile boolean libraryResident = false;

    private static volatile Platform resolvedPlatform;

    private NativeLoader() {
    }

    /**
     * Resets the one-shot latch for a deliberate in-process restart
     * (diagnostics lifecycle churn). The JVM keeps the library
     * resident forever, so the next loadAndInitialize must skip the
     * System.load call and only re-run initialization.
     */
    public static synchronized void resetForRelink() {
        /*
         * Only meaningful after a successful first link; a fresh JVM
         * (never loaded) must keep libraryResident=false so the next
         * loadAndInitialize actually performs System.load.
         */
        if (!libraryResident) {
            return;
        }
        loadAttempted = false;
        loaded = false;
    }

    /**
     * Full resolution + load + validation. Idempotent.
     *
     * @return true when the native core is ready for use
     *         (loaded AND initialize() validated).
     */
    public static synchronized boolean loadAndInitialize(
            NativeHandle handle) {

        if (loadAttempted) {
            return loaded;
        }

        loadAttempted = true;


        Platform platform = Platform.detect();

        resolvedPlatform = platform;


        /*
         * Step 1-2: detect + select.
         */
        if (!platform.isSupported()) {

            System.out.println(
                "[LTE] Unsupported platform ("
                + platform.identifier()
                + ") - native backend disabled, using CPU");

            return false;
        }

        String resourcePath = platform.nativeResourcePath();

        String fileName = platform.libraryFileName();


        System.out.println(
            "[LTE] Platform detected: "
            + platform.identifier());


        /*
         * Step 3: extract from JAR.
         */
        String absolutePath =
            NativeLibraryExtractor.extract(
                platform,
                resourcePath,
                fileName,
                handle.version());

        if (absolutePath == null) {

            /*
             * No binary bundled for this platform - expected on
             * architectures LTE has not been built for yet. Not an
             * error; CPU fallback covers it.
             */
            System.out.println(
                "[LTE] No native binary for "
                + platform.identifier()
                + " in this build - native backend disabled, "
                + "using CPU");

            return false;
        }


        /*
         * Step 4: explicit absolute-path load. The JVM refuses to
         * load the same library twice into one classloader, so on a
         * re-link after shutdown we keep the resident image and only
         * re-arm the loaded flag.
         */
        if (!libraryResident) {

            try {

                System.load(absolutePath);

                System.out.println("[LTE] Native library loaded!");

            } catch (Throwable t) {

                System.out.println(
                    "[LTE] Native load failed on "
                    + platform.identifier() + ": "
                    + t.getMessage());

                return false;
            }
        }

        libraryResident = true;
        loaded = true;


        /*
         * Step 5: validate native initialization.
         */
        try {

            handle.initialize();

            System.out.println(
                "[LTE] Native core initialized (v"
                + handle.version() + ")");

        } catch (Throwable t) {

            System.out.println(
                "[LTE] Native initialize() failed: "
                + t.getMessage());

            loaded = false;

            return false;
        }


        return true;
    }

    /** True after a successful load + validated initialize(). */
    public static boolean isLoaded() {
        return loaded;
    }

    /** Platform this JVM is running on (always available). */
    public static Platform platform() {

        Platform p = resolvedPlatform;

        return p != null ? p : Platform.detect();
    }

    /**
     * Abstraction over the LTENative static API so the loader has no
     * compile-time dependency on the GPU package (and so tests can
     * substitute a fake handle).
     */
    public interface NativeHandle {

        /** Mod/native version string used for extraction scoping. */
        String version();

        /** Trigger native-side initialization. May throw. */
        void initialize();
    }
}

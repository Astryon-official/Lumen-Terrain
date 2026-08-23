package com.astryon.lte.platform;

/*
 * Normalized platform descriptor: OS family + CPU architecture.
 *
 * Detection is deliberately conservative and never touches OS-specific
 * filesystem paths (e.g. /proc/cpuinfo) - only Java properties.
 */
public final class Platform {

    public enum OS { WINDOWS, LINUX, MACOS, UNKNOWN }

    public enum Arch { X86_64, ARM64, UNKNOWN }

    public final OS os;
    public final Arch arch;

    private Platform(OS os, Arch arch) {
        this.os = os;
        this.arch = arch;
    }

    /** Cached detection result. */
    private static volatile Platform current;

    public static Platform detect() {
        Platform p = current;
        if (p == null) {
            current = p = fromSystemProperties(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
        }
        return p;
    }

    /**
     * Pure mapping function - unit-testable without touching the JVM.
     */
    public static Platform fromSystemProperties(String osName, String osArch) {

        return new Platform(parseOS(osName), parseArch(osArch));
    }

    static OS parseOS(String osName) {

        String name = osName.toLowerCase();

        if (name.contains("win"))   return OS.WINDOWS;
        if (name.contains("linux") || name.contains("nix")
                 || name.contains("nux"))  return OS.LINUX;

        if (name.contains("mac") || name.contains("darwin"))
            return OS.MACOS;

        return OS.UNKNOWN;
    }

    static Arch parseArch(String osArch) {

        String arch = osArch.toLowerCase();

        /*
         * Ordered checks - "aarch64" must match ARM64 before any
         * x86 substring logic could interfere.
         */
        if (arch.contains("aarch64"))  return Arch.ARM64;
        if (arch.contains("arm64"))    return Arch.ARM64;

        if (arch.contains("amd64") || arch.contains("x86_64")
                || arch.contains("x64"))      return Arch.X86_64;

        return Arch.UNKNOWN;
    }

    /**
     * Resource directory inside the mod JAR for this platform,
     * e.g. "native/windows-x86_64/" or "native/linux-arm64/".
     */
    public String nativeResourceDir() {

        return "native/" + identifier() + "/";
    }

    /** Library filename convention per OS. */
    public String libraryFileName() {

        switch (os) {
            case WINDOWS: return "LumenTerrainEngine.dll";
            case LINUX:   return "libLumenTerrainEngine.so";
            case MACOS:   return "libLumenTerrainEngine.dylib";
            default:      return null;
        }
    }

    /**
     * Full JAR resource path of the bundled library for this
     * platform, or null when the platform is unsupported.
     */
    public String nativeResourcePath() {

        String file = libraryFileName();

        return file == null ? null : nativeResourceDir() + file;
    }

    /** Stable directory/file identifier, e.g. "windows-x86_64". */
    public String identifier() {

        String osPart;

        switch (os) {
            case WINDOWS: osPart = "windows"; break;
            case LINUX:   osPart = "linux";   break;
            case MACOS:   osPart = "macos";   break;
            default:      osPart = "unknown"; break;
        }

        String archPart;

        switch (arch) {
            case X86_64: archPart = "x86_64"; break;
            case ARM64:  archPart = "arm64";  break;
            default:     archPart = "unknown"; break;
        }

        return osPart + "-" + archPart;
    }

    public boolean isSupported() {
        return os != OS.UNKNOWN && arch != Arch.UNKNOWN;
    }

    @Override
    public String toString() {
        return identifier();
    }
}

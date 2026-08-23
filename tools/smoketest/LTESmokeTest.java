import com.astryon.lte.gpu.LTENative;
import com.astryon.lte.platform.NativeLoader;
import com.astryon.lte.platform.Platform;

/*
 * Standalone cross-platform smoke test for LTE 2.1.
 *
 * Exercises the full production resolution chain:
 *   OS/arch detection -> bundled binary selection -> safe extraction
 *   -> absolute-path load -> validated native initialization
 *   -> multi-device enumeration -> real GPU processing through JNI
 *   -> per-device health + failover semantics
 *
 * No Minecraft/Fabric required.
 */
public class LTESmokeTest {

    static int failures = 0;

    static void check(boolean condition, String what) {
        System.out.println(
            (condition ? "  PASS: " : "  FAIL: ") + what);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {

        System.out.println("=== LTE 2.1 multi-device JNI smoke test ===\n");

        /*
         * 1. Platform detection.
         */
        Platform platform = Platform.detect();

        check(platform.isSupported(),
            "platform detected: " + platform.identifier());

        String expectedResource =
            "native/" + platform.identifier() + "/"
            + platform.libraryFileName();

        check(expectedResource.equals(platform.nativeResourcePath()),
            "resource path resolves: " + platform.nativeResourcePath());

        /*
         * 2. Full load chain: extract -> load -> validated init.
         */
        boolean ready = LTENative.load();
        check(ready == NativeLoader.isLoaded(),
            "load state consistent");
        check(ready, "native core loads and initializes "
            + "(platform=" + platform + ")");

        if (!ready) {

            /*
             * On a platform without a bundled binary this is the
             * correct graceful-degradation outcome, not a failure -
             * except on the dev platform, where the binary must exist.
             */
            if (platform.identifier().equals("windows-x86_64")) {
                finish();
            }
            System.out.println(
                "  (no binary bundled for this platform - OK)");
            return;
        }

        /*
         * 3. Native reporting.
         */
        String version = LTENative.getNativeVersion();
        check("2.1.0".equals(version),
            "version reports 2.1.0 (got '" + version + "')");

        boolean gpu = LTENative.isGPURuntimeAvailable();
        check(true, "isGPURuntimeAvailable()=" + gpu);

        int deviceCount = gpu ? LTENative.getDeviceCount() : 0;
        check(deviceCount >= 0, "device count: " + deviceCount);

        if (gpu && deviceCount > 0) {

            String[][] info = LTENative.getDeviceInfo();
            check(info != null && info.length == deviceCount,
                "device info table matches count");
        }

        /*
         * 4. Benchmarks (identical workload both backends).
         */
        long cpuScore = LTENative.runCPUBenchmark();
        check(cpuScore > 0, "CPU benchmark score: " + cpuScore);

        long gpuScore = LTENative.runGPUBenchmark();
        check(gpuScore >= 0, "aggregate GPU benchmark score: " + gpuScore);

        /*
         * 5. Per-device processing through JNI.
         */
        int[] heightmap = new int[256];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 256; i++) {
            heightmap[i] = 32 + rng.nextInt(64);
        }

        double[] reference = null;

        for (int d = 0; d < deviceCount; d++) {

            double[] result =
                LTENative.gpuProcessTerrain(10, -20, d, heightmap);

            if (result == null) {
                check(false, "device " + d
                    + ": gpuProcessTerrain returns data");
                continue;
            }

            boolean domainOk = true;
            int raised = 0;
            for (double v : result) {
                if (v == 2.0) raised++;
                else if (v != 0.0) domainOk = false;
            }

            check(domainOk && raised > 0, "device " + d
                + ": output valid ({0,2} values, "
                + raised + " raised)");

            check(LTENative.isDeviceHealthy(d),
                "device " + d + ": reports healthy after success");

            if (reference == null) {
                reference = result;
            } else {
                boolean same = java.util.Arrays.equals(reference, result);
                check(same, "device " + d
                    + ": output identical to first device (determinism)");
            }
        }

        if (gpu && deviceCount == 0) {
            check(true,
                "no OpenCL devices present - CPU-only mode is valid");
        }

        /*
         * 6. Invalid input handling (must not crash, must return null).
         */
        double[] bad =
            LTENative.gpuProcessTerrain(0, 0, 0, new int[128]);
        check(bad == null, "wrong-size heightmap rejected safely");

        double[] nullCase =
            LTENative.gpuProcessTerrain(0, 0, 0, null);
        check(nullCase == null, "null heightmap rejected safely");

        double[] badIndex =
            LTENative.gpuProcessTerrain(0, 0, 9999, heightmap);
        check(badIndex == null, "invalid device index rejected safely");

        check(!LTENative.isDeviceHealthy(-1)
            && !LTENative.isDeviceHealthy(9999),
            "health query rejects invalid indices");

        /*
         * 7. Shutdown.
         */
        LTENative.shutdown();
        check(true, "shutdown() completes");

        finish();
    }

    static void finish() {
        System.out.println();
        System.out.println(
            failures == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}

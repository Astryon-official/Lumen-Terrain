package com.astryon.lte.benchmark;

import com.astryon.lte.gpu.LTENative;
import com.astryon.lte.core.Backend;
import com.astryon.lte.core.BackendSelector;
import com.astryon.lte.core.HardwareProfile;
import com.astryon.lte.core.HardwareProfileManager;

/*
 * Hardware benchmark + backend selection.
 *
 * Both benchmarks run the IDENTICAL terrain workload natively
 * (CPU reference vs OpenCL), so scores are directly comparable on
 * any machine. Results are cached per GPU device name; a hardware
 * change invalidates the cache automatically.
 */
public class LTEBenchmark {

    public static Backend selectedBackend = Backend.CPU;

    public static long cpuScore;
    public static long gpuScore;

    public static void run() {

        HardwareProfile profile =
            HardwareProfileManager.load();


        if (profile != null) {

            System.out.println(
                "[LTE] Using cached hardware profile"
            );

            try {
                selectedBackend =
                    Backend.valueOf(profile.backend);
            } catch (Exception e) {
                selectedBackend = Backend.CPU;
            }


            cpuScore = profile.cpuScore;
            gpuScore = profile.gpuScore;


            System.out.println(
                "[LTE] Cached CPU score: " + cpuScore
            );

            System.out.println(
                "[LTE] Cached GPU score: " + gpuScore
            );

            System.out.println(
                "[LTE] Backend: "
                + selectedBackend
            );

            return;
        }


        System.out.println(
            "[LTE] No hardware profile found"
        );


        System.out.println(
            "[LTE] Running hardware benchmark..."
        );


        /*
         * Identical native workload for both backends.
         * 0 means "backend unavailable" and loses selection.
         */
        cpuScore = LTENative.runCPUBenchmark();
        gpuScore = LTENative.runGPUBenchmark();


        System.out.println(
            "[LTE] CPU Score: "
            + cpuScore
        );

        System.out.println(
            "[LTE] GPU Score: "
            + gpuScore
        );


        selectedBackend =
            BackendSelector.choose(
                cpuScore,
                gpuScore
            );


        /*
         * Profile cache identity: every enumerated device's name, so
         * adding/removing a GPU invalidates cached scores.
         */
        String deviceName =
            com.astryon.lte.gpu.DeviceScheduler.usableDeviceCount() > 0
                ? com.astryon.lte.gpu.DeviceScheduler.describeDevices()
                : "None";


        HardwareProfile newProfile =
            new HardwareProfile(
                cpuScore,
                gpuScore,
                selectedBackend.name(),
                deviceName
            );


        HardwareProfileManager.save(
            newProfile
        );


        System.out.println(
            "[LTE] Benchmark complete"
        );
    }
}

package com.astryon.lte;

import com.astryon.lte.core.Backend;
import com.astryon.lte.core.LumenEngine;
import com.astryon.lte.gpu.DeviceScheduler;
import com.astryon.lte.gpu.LTENative;
import com.astryon.lte.benchmark.LTEBenchmark;

import net.fabricmc.api.ModInitializer;

public class LumenTerrainEngine implements ModInitializer {

    public static final String MOD_ID = "lumenterrain";

    public static final String VERSION = "2.1.0";

    private static com.astryon.lte.core.LTEWorkerPool workerPool;

    @Override
    public void onInitialize() {

        System.out.println("[Lumen Terrain Engine] Initializing...");

        LumenEngine.initialize();

        LTEConfigBridge.load();


        /*
         * Native core bring-up through the cross-platform resolver.
         * Every step is defensive: any failure (unsupported platform,
         * no bundled binary, load error, init failure) leaves the mod
         * running with the pure-Java CPU backend.
         */
        if (LTENative.load()) {

            System.out.println(
                "[LTE] Native version: "
                + safe(LTENative.getNativeVersion()));

            System.out.println(
                "[LTE] GPU runtime available: "
                + LTENative.isGPURuntimeAvailable());


            /*
             * Multi-device bring-up (LTE 2.1): enumerate every OpenCL
             * device, benchmark each one, build the scheduler table.
             * CPU-only mode is a valid outcome.
             */
            DeviceScheduler.initialize();

            System.out.println(
                "[LTE] Usable GPU devices: "
                + DeviceScheduler.usableDeviceCount()
                + " ("
                + DeviceScheduler.describeDevices()
                + ")");


            /*
             * Real benchmark of both backends + backend selection,
             * cached per device in config/lte/hardware.profile.
             */
            LTEBenchmark.run();


            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    try {
                        LTENative.shutdown();
                    } catch (Throwable ignored) {
                    }
                }, "LTE-Native-Shutdown")
            );

        } else {

            /*
             * Expected path on platforms without a bundled binary:
             * stay pure-Java. Never crash a Minecraft server over this.
             */
            System.out.println(
                "[LTE] Native backend unavailable - using CPU backend");

            LTEBenchmark.selectedBackend = Backend.CPU;
        }


        com.astryon.lte.core.LTEWorkerPool.start();


        /*
         * Lifecycle events stop the worker pool on SERVER_STOPPED;
         * register events before first ticks arrive.
         */
        registerServerEvents();


        System.out.println(
            "[Lumen Terrain Engine] Startup complete. Backend: "
            + LTEBenchmark.selectedBackend
        );
    }


    /**
     * Fabric lifecycle events. Registration is defensive so a missing/
     * changed Fabric API entry point can never break server startup.
     */
    private static void registerServerEvents() {

        try {

            com.astryon.lte.events.LTEServerEvents.register();

        } catch (Throwable t) {

            System.out.println(
                "[LTE] Server event registration failed: "
                + t.getMessage());
        }
    }


    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

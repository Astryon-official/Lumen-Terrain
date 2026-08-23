package com.astryon.lte;

/*
 * Internal bridge so the entrypoint does not import the config
 * package directly. Loads config/lte.properties (writing defaults
 * on first run) before any subsystem reads config values.
 */
final class LTEConfigBridge {

    private LTEConfigBridge() {
    }

    static void load() {
        try {
            com.astryon.lte.config.LTEConfig.load();
        } catch (Throwable t) {
            System.out.println(
                "[LTE] Config load failed, using defaults: "
                + t.getMessage());
        }
    }
}

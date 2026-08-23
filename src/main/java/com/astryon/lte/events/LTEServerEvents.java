package com.astryon.lte.events;

import com.astryon.lte.chunk.ChunkPredictor;
import com.astryon.lte.core.LTEWorkerPool;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/*
 * Fabric lifecycle wiring.
 *
 * - END_SERVER_TICK: per-tick player tracking / prediction feed.
 * - SERVER_STOPPED: stops the LTE worker pool so dedicated servers
 *   shut down cleanly instead of hanging on live non-daemon threads.
 */
public class LTEServerEvents {

    public static void register() {

        ServerTickEvents.END_SERVER_TICK.register(server -> {

            for (var player : server.getPlayerList().getPlayers()) {

                ChunkPredictor.predictPlayer(player);

            }

        });


        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {

            LTEWorkerPool.shutdown();

            System.out.println("[LTE] Worker pool stopped");

        });
    }
}

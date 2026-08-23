package com.astryon.lte.core;

import com.astryon.lte.chunk.ChunkProcessor;
import com.astryon.lte.chunk.ChunkQueue;

/*
 * LTE worker thread (2.1 pool member).
 *
 * Parks on a lock/condition when the queue is empty instead of
 * polling every 10 ms. Idle CPU cost drops to zero wakeups.
 *
 * One thread owns one GPU device at a time: DeviceScheduler hands out
 * devices with exclusive busy flags, so two workers never enqueue
 * into the same OpenCL command queue concurrently. CPU fallback work
 * runs fully parallel across the pool (ThreadLocal scratch buffers).
 */
public class LTEWorker extends Thread {

    private static final Object SIGNAL = new Object();

    private volatile boolean running = true;


    public LTEWorker() {

        super("LTE-Worker");

        setDaemon(false);

    }


    public LTEWorker(String name) {

        super(name);

        setDaemon(false);

    }


    /** Wake all parked workers (new work may have arrived). */
    public static void signal() {

        synchronized (SIGNAL) {
            SIGNAL.notifyAll();
        }

    }

    /** Wake all parked workers during shutdown. */
    static void wakeAll() {
        signal();
    }


    @Override
    public void run() {

        System.out.println("[LTE] " + getName() + " started");


        while (running) {

            try {

                ChunkProcessor.process();


                synchronized (SIGNAL) {

                    if (!running) {
                        return;
                    }

                    if (ChunkQueue.getSize() == 0) {
                        SIGNAL.wait(1000);
                    }

                }


            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                return;

            } catch (Exception e) {

                System.out.println(
                    "[LTE] Worker error: "
                    + e.getMessage()
                );

            }

        }

    }


    public void shutdown() {

        running = false;

    }

}

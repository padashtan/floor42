package dev.floor42.server.fixture;

import java.util.concurrent.Executors;

/** A planted violation: background work started outside the MissionScheduler (ArchitectureRulesTest). */
public final class StartsItsOwnThread {

    private StartsItsOwnThread() {}

    public static void virtual(Runnable work) {
        Thread.ofVirtual().name("rogue").start(work);
    }

    public static void platform(Runnable work) {
        new Thread(work, "rogue").start();
    }

    public static void pool(Runnable work) {
        Executors.newSingleThreadExecutor().submit(work);
    }
}

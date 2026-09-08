package com.tinsl.nitro.client;

import java.util.concurrent.atomic.AtomicLong;

/** Counts client resource reloads (model manager reloads); used to scope per-reload caches. */
public final class ReloadGeneration {
    private static final AtomicLong GENERATION = new AtomicLong();

    private ReloadGeneration() {}

    public static long current() {
        return GENERATION.get();
    }

    public static void next() {
        GENERATION.incrementAndGet();
    }
}

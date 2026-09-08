package com.tinsl.nitro.recipes;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.tinsl.nitro.Nitro;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decodes recipe JSON on all cores while {@code RecipeManager.apply} walks its entries on one thread.
 *
 * Vanilla/NeoForge decode every recipe sequentially on the thread that applies the data pack
 * (the render thread for single-player, the server thread for dedicated servers). With 141k
 * recipes that is a noticeable slice of the "loading world" time. Codec decoding is a pure
 * function of (ops, json), so the work is farmed out in chunks and the sequential loop only
 * collects results, preserving the original iteration order and error handling.
 *
 * Any entry whose parallel decode threw (rather than returning an error DataResult) is retried
 * sequentially by the original code path, so a codec that is not thread-safe degrades to vanilla
 * behaviour instead of breaking recipes.
 */
public final class ParallelRecipeDecoder {
    private static final int CHUNK = 256;
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private ParallelRecipeDecoder() {}

    private static final class Session {
        final Map<ResourceLocation, JsonElement> map;
        final ConcurrentHashMap<ResourceLocation, Object> results = new ConcurrentHashMap<>();
        final Map<ResourceLocation, CompletableFuture<?>> chunkOf = new HashMap<>();
        final long start = System.nanoTime();
        int hits;
        int retries;
        int misses;

        Session(Map<ResourceLocation, JsonElement> map) {
            this.map = map;
        }
    }

    /**
     * @return the DataResult for this entry, or {@code null} if the caller must invoke the original decode.
     */
    @SuppressWarnings("unchecked")
    public static DataResult<?> lookup(Map<ResourceLocation, JsonElement> map, ResourceLocation id,
                                       Codec<?> codec, DynamicOps<?> ops, Object input) {
        Session session = CURRENT.get();
        if (session == null || session.map != map) {
            session = begin(map, codec, ops);
            CURRENT.set(session);
        }
        Object r = session.results.get(id);
        if (r == null) {
            CompletableFuture<?> chunk = session.chunkOf.get(id);
            if (chunk != null) {
                chunk.join();
                r = session.results.get(id);
            }
        }
        if (r instanceof DataResult<?> result) {
            session.hits++;
            return result;
        }
        if (r instanceof Throwable) {
            session.retries++;
        } else {
            session.misses++;
        }
        return null;
    }

    /** Called when RecipeManager.apply returns; logs statistics and drops the session. */
    public static void end() {
        Session session = CURRENT.get();
        if (session == null) {
            return;
        }
        CURRENT.remove();
        double ms = (System.nanoTime() - session.start) / 1_000_000.0;
        Nitro.LOGGER.info("Nitro: recipe decode finished in {} ms ({} decoded in parallel, {} retried sequentially, {} not pre-decoded)",
            String.format("%.0f", ms), session.hits, session.retries, session.misses);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Session begin(Map<ResourceLocation, JsonElement> map, Codec<?> codec, DynamicOps<?> ops) {
        Session session = new Session(map);
        List<Map.Entry<ResourceLocation, JsonElement>> entries = new ArrayList<>(map.size());
        for (Map.Entry<ResourceLocation, JsonElement> e : map.entrySet()) {
            if (!e.getKey().getPath().startsWith("_")) {
                entries.add(e);
            }
        }
        for (int from = 0; from < entries.size(); from += CHUNK) {
            List<Map.Entry<ResourceLocation, JsonElement>> chunk = entries.subList(from, Math.min(entries.size(), from + CHUNK));
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (Map.Entry<ResourceLocation, JsonElement> e : chunk) {
                    Object out;
                    try {
                        out = ((Codec) codec).parse((DynamicOps) ops, e.getValue());
                    } catch (Throwable t) {
                        out = t;
                    }
                    session.results.put(e.getKey(), out);
                }
            }, Util.backgroundExecutor());
            for (Map.Entry<ResourceLocation, JsonElement> e : chunk) {
                session.chunkOf.put(e.getKey(), future);
            }
        }
        Nitro.LOGGER.info("Nitro: decoding {} recipes in parallel on {} threads", entries.size(), Runtime.getRuntime().availableProcessors());
        return session;
    }
}

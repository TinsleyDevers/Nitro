package com.tinsl.nitro.client;

import com.tinsl.nitro.Nitro;
import net.minecraft.client.resources.model.ModelResourceLocation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups the model universe (4+ million locations) by namespace, once per universe instance, so
 * that a mod restricted to "its own models plus its dependencies'" gets a small materialised set
 * instead of a filtered view that still walks all 4 million entries on every iteration.
 */
public final class NamespaceIndex {
    private static final Object LOCK = new Object();
    private static WeakReference<Set<ModelResourceLocation>> indexedSet = new WeakReference<>(null);
    private static int indexedSize = -1;
    private static Map<String, ModelResourceLocation[]> index;

    private NamespaceIndex() {}

    /** Same contents as {@code Sets.filter(universe, loc -> namespaces.contains(loc.id().getNamespace()))}, materialised. */
    public static Set<ModelResourceLocation> subset(Set<ModelResourceLocation> universe, Set<String> namespaces) {
        Map<String, ModelResourceLocation[]> idx = indexFor(universe);
        int total = 0;
        for (String ns : namespaces) {
            ModelResourceLocation[] arr = idx.get(ns);
            if (arr != null) {
                total += arr.length;
            }
        }
        Set<ModelResourceLocation> out = new LinkedHashSet<>(Math.max(16, total * 4 / 3 + 1));
        for (String ns : namespaces) {
            ModelResourceLocation[] arr = idx.get(ns);
            if (arr != null) {
                for (ModelResourceLocation l : arr) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    private static Map<String, ModelResourceLocation[]> indexFor(Set<ModelResourceLocation> universe) {
        synchronized (LOCK) {
            if (indexedSet.get() == universe && indexedSize == universe.size()) {
                return index;
            }
            long start = System.nanoTime();
            Map<String, List<ModelResourceLocation>> groups = new HashMap<>(512);
            for (ModelResourceLocation l : universe) {
                groups.computeIfAbsent(l.id().getNamespace(), k -> new ArrayList<>()).add(l);
            }
            Map<String, ModelResourceLocation[]> built = new HashMap<>(groups.size() * 2);
            for (Map.Entry<String, List<ModelResourceLocation>> e : groups.entrySet()) {
                built.put(e.getKey(), e.getValue().toArray(new ModelResourceLocation[0]));
            }
            indexedSet = new WeakReference<>(universe);
            indexedSize = universe.size();
            index = built;
            Nitro.LOGGER.info("Nitro: indexed {} model locations by namespace ({} namespaces) in {} ms for restricted model-bake handlers",
                indexedSize, built.size(), (System.nanoTime() - start) / 1_000_000L);
            return built;
        }
    }
}

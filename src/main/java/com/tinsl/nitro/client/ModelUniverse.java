package com.tinsl.nitro.client;

import net.minecraft.client.resources.model.ModelResourceLocation;

import java.util.Set;

/**
 * The set of every top-level model location of the current resource reload, as computed by
 * ModernFix's model-bake helper (block state variants of every block, "inventory" of every item and
 * item model file, plus every explicitly registered model). Shared so that the second ModernFix
 * helper of the same reload and Puzzles Lib's model passes do not compute it again.
 */
public final class ModelUniverse {
    private static final Object LOCK = new Object();
    private static long generation = -1;
    private static Set<ModelResourceLocation> locations;
    private static Set<String> namespaces;

    private ModelUniverse() {}

    public static void publish(Set<ModelResourceLocation> locs, Set<String> ns) {
        synchronized (LOCK) {
            generation = ReloadGeneration.current();
            locations = locs;
            namespaces = ns;
        }
    }

    /** @return the universe computed during the current reload, or null if none yet. */
    public static Set<ModelResourceLocation> currentLocations() {
        synchronized (LOCK) {
            return generation == ReloadGeneration.current() ? locations : null;
        }
    }

    public static Set<String> currentNamespaces() {
        synchronized (LOCK) {
            return generation == ReloadGeneration.current() ? namespaces : null;
        }
    }
}

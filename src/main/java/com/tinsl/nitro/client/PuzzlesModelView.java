package com.tinsl.nitro.client;

import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Read-only view of ModernFix's model universe filtered down to what Puzzles Lib's own
 * {@code getTopLevelModelLocations} would contain: block-state variants of registered blocks,
 * "inventory" variants of registered items, plus the three fixed entries Puzzles adds itself.
 *
 * The first iteration snapshots the filtered result into a flat array (one pass over the 4-million
 * entry linked hash set, ~0.8 s); the four Puzzles passes then iterate the array, which is an order
 * of magnitude cheaper than walking the hash set each time. Only iteration is used by Puzzles Lib.
 */
public final class PuzzlesModelView extends AbstractSet<ModelResourceLocation> {
    private static final List<ModelResourceLocation> EXTRAS = List.of(
        ModelBakery.MISSING_MODEL_VARIANT, ItemRenderer.TRIDENT_IN_HAND_MODEL, ItemRenderer.SPYGLASS_IN_HAND_MODEL);
    private final Set<ModelResourceLocation> universe;
    private volatile ModelResourceLocation[] snapshot;

    public PuzzlesModelView(Set<ModelResourceLocation> universe) {
        this.universe = universe;
    }

    static boolean accept(ModelResourceLocation loc) {
        String variant = loc.variant();
        if ("inventory".equals(variant)) {
            return BuiltInRegistries.ITEM.containsKey(loc.id());
        }
        if ("standalone".equals(variant)) {
            return false;
        }
        return BuiltInRegistries.BLOCK.containsKey(loc.id());
    }

    private ModelResourceLocation[] items() {
        ModelResourceLocation[] s = snapshot;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (snapshot != null) {
                return snapshot;
            }
            List<ModelResourceLocation> out = new ArrayList<>(universe.size());
            // consecutive entries share their block id object (all states of one block), so the
            // registry lookup happens once per run of equal ids instead of once per state
            ResourceLocation lastId = null;
            boolean lastAccepted = false;
            for (ModelResourceLocation l : universe) {
                String variant = l.variant();
                boolean ok;
                if ("inventory".equals(variant) || "standalone".equals(variant)) {
                    ok = accept(l);
                } else if (l.id() == lastId) {
                    ok = lastAccepted;
                } else {
                    ok = accept(l);
                    lastId = l.id();
                    lastAccepted = ok;
                }
                if (ok) {
                    out.add(l);
                }
            }
            for (ModelResourceLocation extra : EXTRAS) {
                if (!(accept(extra) && universe.contains(extra))) {
                    out.add(extra);
                }
            }
            snapshot = out.toArray(new ModelResourceLocation[0]);
            return snapshot;
        }
    }

    @Override
    public Iterator<ModelResourceLocation> iterator() {
        return Arrays.asList(items()).iterator();
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof ModelResourceLocation l && ((accept(l) && universe.contains(l)) || EXTRAS.contains(l));
    }

    @Override
    public int size() {
        return items().length;
    }
}

package com.tinsl.nitro.mixin.puzzles;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.client.ModelUniverse;
import com.tinsl.nitro.client.PuzzlesModelView;
import com.tinsl.nitro.client.ReloadGeneration;
import net.minecraft.client.resources.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Set;

/**
 * Puzzles Lib rebuilds the set of every top-level model location (one per block state plus one
 * per item, 4.35 million entries on this pack) for each of its model-modification passes, and
 * there is one pass per mod that registered a callback. Building it once takes ~6.5 s on the
 * single model-loading thread.
 *
 * ModernFix computes the same block-state and item locations for its own model-bake helper right
 * before these passes run. When that set is available for the current reload, Puzzles gets a
 * filtered, non-materialised view of it ({@link PuzzlesModelView}) instead of a fresh copy.
 * Without ModernFix the set is computed by Puzzles' own code once and reused for all passes; both
 * callers only iterate it.
 */
@Mixin(targets = "fuzs.puzzleslib.neoforge.impl.client.event.NeoForgeClientEventInvokers", remap = false)
public abstract class PuzzlesTopLevelModelsMixin {
    @Unique private static final Object NITRO_LOCK = new Object();
    @Unique private static Set<ModelResourceLocation> nitro$cache;
    @Unique private static long nitro$cacheGeneration = -1;
    @Unique private static boolean nitro$cacheIsView;

    @Inject(method = "getTopLevelModelLocations", at = @At("HEAD"), cancellable = true)
    private static void nitro$cachedTopLevel(CallbackInfoReturnable<Set<ModelResourceLocation>> cir) {
        synchronized (NITRO_LOCK) {
            long gen = ReloadGeneration.current();
            if (nitro$cache != null && (!nitro$cacheIsView || nitro$cacheGeneration == gen)) {
                cir.setReturnValue(nitro$cache);
                return;
            }
            Set<ModelResourceLocation> universe = ModelUniverse.currentLocations();
            if (universe != null) {
                nitro$cache = new PuzzlesModelView(universe);
                nitro$cacheIsView = true;
                nitro$cacheGeneration = gen;
                Nitro.LOGGER.info("Nitro: Puzzles Lib model passes iterate ModernFix's model universe ({} entries) instead of rebuilding it", universe.size());
                cir.setReturnValue(nitro$cache);
            }
        }
    }

    @Inject(method = "getTopLevelModelLocations", at = @At("RETURN"), cancellable = true)
    private static void nitro$storeTopLevel(CallbackInfoReturnable<Set<ModelResourceLocation>> cir) {
        Set<ModelResourceLocation> computed = cir.getReturnValue();
        synchronized (NITRO_LOCK) {
            if (computed != null && !(computed instanceof PuzzlesModelView) && (nitro$cache == null || nitro$cacheIsView)) {
                Set<ModelResourceLocation> view = Collections.unmodifiableSet(computed);
                nitro$cache = view;
                nitro$cacheIsView = false;
                cir.setReturnValue(view);
                Nitro.LOGGER.info("Nitro: cached {} top-level model locations for Puzzles Lib model passes", computed.size());
            }
        }
    }
}

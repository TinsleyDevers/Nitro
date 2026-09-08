package com.tinsl.nitro.mixin.sable;

import com.tinsl.nitro.Nitro;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable applies its data-driven physics block properties to every block state of every block each
 * time a ServerLevel is constructed. The properties are stored on the (global) BlockState objects
 * and depend only on the loaded definitions, so with 46 dimensions the same work is done 46 times
 * (~0.3 s each, all on the server thread while the world opens). Apply once per data pack load.
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertiesDefinitionLoader", remap = false)
public abstract class SableApplyOnceMixin {
    @Unique
    private static volatile boolean nitro$dirty = true;

    /** Definitions were (re)loaded from data packs: the next applyAll must run. */
    @Inject(method = "apply", at = @At("RETURN"))
    private void nitro$markDirty(CallbackInfo ci) {
        nitro$dirty = true;
    }

    @Inject(method = "applyAll", at = @At("HEAD"), cancellable = true)
    private void nitro$applyOnce(CallbackInfo ci) {
        if (!nitro$dirty) {
            ci.cancel();
            return;
        }
        Nitro.LOGGER.info("Nitro: applying Sable physics block properties once for this data pack load (skipping the repeat per dimension)");
    }

    @Inject(method = "applyAll", at = @At("RETURN"))
    private void nitro$markApplied(CallbackInfo ci) {
        nitro$dirty = false;
    }
}

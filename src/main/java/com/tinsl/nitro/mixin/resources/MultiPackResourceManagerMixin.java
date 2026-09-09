package com.tinsl.nitro.mixin.resources;

import com.tinsl.nitro.resources.PackIndex;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Every reload builds one of these with the full pack list, which is the first moment Nitro knows
 * which packs exist. That is where the resource index warm-up starts, so the walks happen on the
 * idle cores while the reload is still setting itself up instead of one at a time on the reload
 * thread later.
 */
@Mixin(MultiPackResourceManager.class)
public abstract class MultiPackResourceManagerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void nitro$warmPackIndexes(PackType type, List<PackResources> packs, CallbackInfo ci) {
        try {
            PackIndex.warm(type, packs);
        } catch (Throwable ignored) {
            // warming is an optimisation; the on-demand path still works
        }
    }
}

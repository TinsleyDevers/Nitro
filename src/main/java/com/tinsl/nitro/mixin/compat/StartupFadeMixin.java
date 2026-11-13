package com.tinsl.nitro.mixin.compat;

import net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Slice;

/** Keep a short transition after resources finish so the menu becomes usable sooner. */
@Mixin(value = NeoForgeLoadingOverlay.class, remap = false)
public abstract class StartupFadeMixin {
    @ModifyConstant(method = "render", constant = @Constant(floatValue = 1000.0F),
        slice = @Slice(
            from = @At(value = "FIELD", target = "Lnet/neoforged/neoforge/client/loading/NeoForgeLoadingOverlay;fadeOutStart:J"),
            to = @At(value = "FIELD", target = "Lnet/neoforged/neoforge/client/loading/NeoForgeLoadingOverlay;currentProgress:F")))
    private float nitro$shorterFade(float duration) {
        return 200.0F;
    }
}

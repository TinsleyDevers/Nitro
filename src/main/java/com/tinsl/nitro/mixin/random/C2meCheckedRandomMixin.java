package com.tinsl.nitro.mixin.random;

import com.tinsl.nitro.jei.AsyncJeiStarter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C2ME wraps {@code Level.random} in CheckedThreadLocalRandom: non-owner threads transparently get
 * a private fallback random (safe), but every access also logs a full stack trace. The Nitro JEI
 * startup thread is a known, intentional off-thread user, so skip the report for it.
 * Only applied when C2ME's worldgen threading fixes module is present (see NitroMixinPlugin).
 */
@Mixin(targets = "com.ishland.c2me.fixes.worldgen.threading_issues.common.CheckedThreadLocalRandom", remap = false)
public abstract class C2meCheckedRandomMixin {
    @Inject(method = "handleNotOwner", at = @At("HEAD"), cancellable = true)
    private void nitro$quietForJeiThread(CallbackInfo ci) {
        if (AsyncJeiStarter.isJeiStartupThread()) {
            ci.cancel();
        }
    }
}

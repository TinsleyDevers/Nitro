package com.tinsl.nitro.mixin.jei;

import com.tinsl.nitro.jei.AsyncJeiStarter;
import mezz.jei.common.util.ErrorUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JEI guards several API entry points (ingredient manager, recipe manager, visibility) with
 * {@code ErrorUtil.assertMainThread()}. While Nitro runs JEI's own startup on its dedicated
 * thread, that thread is the legitimate owner of those structures, so the assertion is relaxed
 * for it only. Any other thread is still rejected exactly as before.
 */
@Mixin(value = ErrorUtil.class, remap = false)
public abstract class ErrorUtilMixin {
    @Inject(method = "assertMainThread", at = @At("HEAD"), cancellable = true)
    private static void nitro$allowJeiStartupThread(CallbackInfo ci) {
        if (AsyncJeiStarter.isJeiStartupThread()) {
            ci.cancel();
        }
    }
}

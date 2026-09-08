package com.tinsl.nitro.mixin.jei;

import com.tinsl.nitro.jei.AsyncJeiStarter;
import mezz.jei.library.startup.JeiStarter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = JeiStarter.class, remap = false)
public abstract class JeiStarterMixin {
    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void nitro$startAsync(CallbackInfo ci) {
        if (AsyncJeiStarter.isBypassed()) {
            return;
        }
        AsyncJeiStarter.start((JeiStarter) (Object) this);
        ci.cancel();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void nitro$onStop(CallbackInfo ci) {
        AsyncJeiStarter.onStop();
    }
}

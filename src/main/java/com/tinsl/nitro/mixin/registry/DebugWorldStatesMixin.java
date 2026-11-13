package com.tinsl.nitro.mixin.registry;

import net.minecraft.world.level.levelgen.DebugLevelSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugLevelSource.class)
public abstract class DebugWorldStatesMixin {
    @Unique private static volatile boolean nitro$statesDirty;
    @Unique private static boolean nitro$buildingStates;

    @Inject(method = "initValidStates", at = @At("HEAD"), cancellable = true)
    private static void nitro$invalidateDebugStates(CallbackInfo callback) {
        synchronized (DebugLevelSource.class) {
            if (!nitro$buildingStates) {
                nitro$statesDirty = true;
                callback.cancel();
            }
        }
    }

    @Inject(method = "getBlockStateFor", at = @At("HEAD"))
    private static void nitro$ensureDebugStates(int x, int z, CallbackInfoReturnable<?> callback) {
        if (!nitro$statesDirty) {
            return;
        }
        synchronized (DebugLevelSource.class) {
            if (nitro$statesDirty) {
                nitro$buildingStates = true;
                try {
                    DebugLevelSource.initValidStates();
                    nitro$statesDirty = false;
                } finally {
                    nitro$buildingStates = false;
                }
            }
        }
    }
}

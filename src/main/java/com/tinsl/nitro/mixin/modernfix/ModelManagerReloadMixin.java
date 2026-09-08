package com.tinsl.nitro.mixin.modernfix;

import com.tinsl.nitro.client.ReloadGeneration;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Bumps the reload generation at the start of every model reload (see {@link ModelBakeEventHelperCacheMixin}). */
@Mixin(ModelManager.class)
public abstract class ModelManagerReloadMixin {
    @Inject(method = "reload", at = @At("HEAD"))
    private void nitro$nextGeneration(PreparableReloadListener.PreparationBarrier barrier, ResourceManager manager,
                                      ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                                      Executor prepareExecutor, Executor applyExecutor,
                                      CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ReloadGeneration.next();
    }
}

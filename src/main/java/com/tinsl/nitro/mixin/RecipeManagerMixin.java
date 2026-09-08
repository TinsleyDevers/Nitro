package com.tinsl.nitro.mixin;

import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.tinsl.nitro.recipes.ParallelRecipeDecoder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Wraps the single {@code Codec.parse} call inside {@code RecipeManager.apply} so that the first
 * call kicks off a parallel decode of every recipe and subsequent calls just collect results.
 * The surrounding loop, ordering, error logging and other mods' injections into
 * {@code apply} (KubeJS, Almost Unified, ...) are left untouched.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @WrapOperation(
        method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;")
    )
    private DataResult<?> nitro$parallelParse(Codec<?> codec, DynamicOps<?> ops, Object input, Operation<DataResult<?>> original,
                                              @Local(argsOnly = true) Map<ResourceLocation, JsonElement> map,
                                              @Local(ordinal = 0) ResourceLocation id) {
        DataResult<?> precomputed = ParallelRecipeDecoder.lookup(map, id, codec, ops, input);
        if (precomputed != null) {
            return precomputed;
        }
        return original.call(codec, ops, input);
    }

    @Inject(
        method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("RETURN")
    )
    private void nitro$endParallelParse(CallbackInfo ci) {
        ParallelRecipeDecoder.end();
    }
}

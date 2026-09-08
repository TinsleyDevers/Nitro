package com.tinsl.nitro.mixin.jaopca;

import com.google.common.collect.Multimap;
import com.tinsl.nitro.Nitro;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JAOPCA remaps the models of its generated blocks/items onto other mods' models. Its handler does
 * {@code Set.copyOf(models.keySet())} and then streams that set once per remap target. Under
 * ModernFix's dynamic model registry the key set is the universe of every possible model location
 * (4.35 million here), so this is a multi-second walk on the single model-loading thread, per reload.
 *
 * The keys it is looking for are fully determined by the target block's state definition (plus the
 * "inventory" and "standalone" variants), so they are computed directly. Result and order of
 * {@code put} calls are the same as the original for every key that exists in the registry.
 */
@Mixin(targets = "thelm.jaopca.client.models.ModelHandler", remap = false)
public abstract class JaopcaModelHandlerMixin {
    @Shadow @Final private static Map<ResourceLocation, ResourceLocation> BLOCK_MODEL_REMAPS;
    @Shadow @Final private static Map<ResourceLocation, ResourceLocation> ITEM_MODEL_REMAPS;
    @Shadow @Final private static Multimap<ResourceLocation, ResourceLocation> BLOCK_MODEL_REMAPS_REVERSE;
    @Shadow @Final private static Multimap<ResourceLocation, ResourceLocation> ITEM_MODEL_REMAPS_REVERSE;

    @Inject(method = "remapModels", at = @At("HEAD"), cancellable = true)
    private static void nitro$remapWithoutRegistryWalk(ModelEvent.ModifyBakingResult event, CallbackInfo ci) {
        Map<ModelResourceLocation, BakedModel> registry = event.getModels();
        int remapped = 0;
        for (Map.Entry<ResourceLocation, Collection<ResourceLocation>> entry : BLOCK_MODEL_REMAPS_REVERSE.asMap().entrySet()) {
            ResourceLocation to = entry.getKey();
            List<ModelResourceLocation> keys = new ArrayList<>();
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(to);
            if (block.isPresent()) {
                for (BlockState state : block.get().getStateDefinition().getPossibleStates()) {
                    keys.add(BlockModelShaper.stateToModelLocation(to, state));
                }
            }
            ModelResourceLocation inventory = ModelResourceLocation.inventory(to);
            if (registry.containsKey(inventory)) {
                keys.add(inventory);
            }
            ModelResourceLocation standalone = ModelResourceLocation.standalone(to);
            if (registry.containsKey(standalone)) {
                keys.add(standalone);
            }
            for (ModelResourceLocation key : keys) {
                BakedModel model = registry.get(key);
                if (model == null) {
                    continue;
                }
                for (ResourceLocation from : entry.getValue()) {
                    registry.put(new ModelResourceLocation(from, key.variant()), model);
                    remapped++;
                }
            }
        }
        for (Map.Entry<ResourceLocation, Collection<ResourceLocation>> entry : ITEM_MODEL_REMAPS_REVERSE.asMap().entrySet()) {
            ModelResourceLocation to = ModelResourceLocation.inventory(entry.getKey());
            if (registry.containsKey(to)) {
                BakedModel model = registry.get(to);
                for (ResourceLocation from : entry.getValue()) {
                    registry.put(ModelResourceLocation.inventory(from), model);
                    remapped++;
                }
            }
        }
        BLOCK_MODEL_REMAPS.clear();
        ITEM_MODEL_REMAPS.clear();
        BLOCK_MODEL_REMAPS_REVERSE.clear();
        ITEM_MODEL_REMAPS_REVERSE.clear();
        Nitro.LOGGER.info("Nitro: applied {} JAOPCA model remaps with direct lookups (no model-registry walk)", remapped);
        ci.cancel();
    }
}

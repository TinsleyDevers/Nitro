package com.tinsl.nitro.mixin.registry;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/** Allocate the block-state ID tables once before NeoForge adds all of the states in registry order. */
@Mixin(targets = "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks", remap = false)
public abstract class BlockStateIdsMixin {
    @Inject(method = "onBake", at = @At("HEAD"))
    private void nitro$reserveBlockStateIds(Registry<Block> blocks, CallbackInfo callback) {
        if (!(Block.BLOCK_STATE_REGISTRY instanceof IdMapperAccess access)
            || !(access.nitro$ids() instanceof Reference2IntOpenHashMap<?> ids)
            || !(access.nitro$values() instanceof ArrayList<?> values)) {
            return;
        }
        int count = 0;
        for (Block block : blocks) {
            count += block.getStateDefinition().getPossibleStates().size();
        }
        ids.ensureCapacity(count);
        values.ensureCapacity(count);
    }
}

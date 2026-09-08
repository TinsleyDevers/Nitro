package com.tinsl.nitro.mixin.farmersdelight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Farmer's Delight's "item ability" ingredient (any knife, any axe, ...) resolves its item list by
 * creating a stack for every registered item (100k here) and testing it. Every recipe using such an
 * ingredient resolves it independently, and the recipe pipeline (KubeJS, JEI, Almost Unified)
 * asks each of them: hundreds of full registry scans per data pack load, on the render thread.
 * The result depends only on the ability, so it is computed once per ability and shared.
 */
@Mixin(targets = "vectorwing.farmersdelight.common.crafting.ingredient.ItemAbilityIngredient", remap = false)
public abstract class ItemAbilityIngredientMixin {
    @Shadow @Final protected ItemAbility itemAbility;
    @Shadow protected Stream<ItemStack> itemStacks;

    @Unique
    private static final Map<ItemAbility, List<ItemStack>> NITRO_CACHE = new ConcurrentHashMap<>();

    @Inject(method = "dissolve", at = @At("HEAD"), cancellable = true)
    private void nitro$sharedDissolve(CallbackInfo ci) {
        if (this.itemStacks != null) {
            ci.cancel();
            return;
        }
        List<ItemStack> stacks = NITRO_CACHE.computeIfAbsent(this.itemAbility, ability -> {
            List<ItemStack> out = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack stack = new ItemStack(item);
                if (stack.canPerformAction(ability)) {
                    out.add(stack);
                }
            }
            return List.copyOf(out);
        });
        this.itemStacks = stacks.stream();
        ci.cancel();
    }
}

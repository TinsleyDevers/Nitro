package com.tinsl.nitro.mixin.megacells;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tinsl.nitro.Nitro;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.*;

/** Only narrows candidates; MEGA Cells still decides reversibility, quantities and overrides. */
@Mixin(targets = "gripe._90.megacells.misc.CompressionService", remap = false)
public abstract class CompressionCandidateMixin {
    @Unique private static final ThreadLocal<IdentityHashMap<List<CraftingRecipe>, Map<Item, List<CraftingRecipe>>>> nitro$indexes = new ThreadLocal<>();

    @WrapMethod(method = "loadRecipes")
    private static void nitro$scope(RecipeManager manager, RegistryAccess access, Operation<Void> original) {
        long start = System.nanoTime();
        nitro$indexes.set(new IdentityHashMap<>());
        try { original.call(manager, access); }
        finally {
            nitro$indexes.remove();
            Nitro.LOGGER.info("Nitro: MEGA Cells compression discovery finished in {} ms with indexed reverse candidates", (System.nanoTime() - start) / 1_000_000);
        }
    }

    @ModifyVariable(method = "isIrreversible", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static List<CraftingRecipe> nitro$candidates(List<CraftingRecipe> candidates, CraftingRecipe recipe,
                                                       List<CraftingRecipe> allCandidates, List<?> overrides, RegistryAccess access) {
        var indexes = nitro$indexes.get();
        if (indexes == null) return candidates;
        // During each of the two removeIf passes the opposite candidate list is immutable.
        // Each list is indexed only when first used, after the preceding pruning pass.
        var index = indexes.computeIfAbsent(candidates, list -> {
            Map<Item, List<CraftingRecipe>> byInput = new IdentityHashMap<>();
            for (CraftingRecipe candidate : list) {
                if (candidate.getIngredients().isEmpty()) continue;
                Set<Item> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var stack : candidate.getIngredients().getFirst().getItems()) {
                    if (seen.add(stack.getItem())) byInput.computeIfAbsent(stack.getItem(), key -> new ArrayList<>()).add(candidate);
                }
            }
            return byInput;
        });
        return index.getOrDefault(recipe.getResultItem(access).getItem(), List.of());
    }
}

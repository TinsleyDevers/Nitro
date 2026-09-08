package com.tinsl.nitro.mixin.hexerei;

import com.tinsl.nitro.Nitro;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Hexerei derives its woodcutter recipes at server start by scanning the recipe manager once per
 * log item (a full {@code getRecipesFor} over all crafting recipes) and once per plank item over
 * the complete recipe list, keeping only crafting recipes whose result is planks, boats, signs,
 * slabs, stairs, doors, trapdoors, buttons, fences, fence gates or pressure plates. With 139k
 * recipes and a few hundred wood items that is tens of millions of result-item checks (14 s on the
 * server thread while the world opens).
 *
 * The recipe list is pre-filtered once per call to exactly the crafting recipes those loops can
 * accept; every loop's own checks still run, so the produced recipes are identical.
 */
@Mixin(targets = "net.joefoxe.hexerei.data.recipes.WoodcutterRecipes", remap = false)
public abstract class HexereiWoodcutterMixin {
    @Unique
    private static final List<TagKey<Item>> NITRO_WOOD_TAGS = List.of(
        ItemTags.PLANKS, ItemTags.BOATS, ItemTags.SIGNS, ItemTags.WOODEN_SLABS, ItemTags.WOODEN_STAIRS,
        ItemTags.WOODEN_DOORS, ItemTags.WOODEN_TRAPDOORS, ItemTags.WOODEN_BUTTONS, ItemTags.WOODEN_FENCES,
        ItemTags.FENCE_GATES, ItemTags.WOODEN_PRESSURE_PLATES);

    @Unique private static List<RecipeHolder<?>> nitro$woodCrafting;
    @Unique private static List<RecipeHolder<?>> nitro$planksCrafting;
    @Unique private static RecipeManager nitro$cachedFor;

    @Inject(method = "createRecipes", at = @At("HEAD"))
    private static void nitro$prefilter(MinecraftServer server, CallbackInfo ci) {
        long start = System.nanoTime();
        RecipeManager manager = server.getRecipeManager();
        RegistryAccess access = server.registryAccess();
        List<RecipeHolder<?>> wood = new ArrayList<>();
        List<RecipeHolder<?>> planks = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.getType() != RecipeType.CRAFTING) {
                continue;
            }
            ItemStack result;
            try {
                result = recipe.getResultItem(access);
            } catch (RuntimeException e) {
                wood.add(holder); // keep anything odd: the original loops would have seen it too
                continue;
            }
            if (result == null) {
                continue;
            }
            boolean any = false;
            for (TagKey<Item> tag : NITRO_WOOD_TAGS) {
                if (result.is(tag)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                wood.add(holder);
                if (result.is(ItemTags.PLANKS)) {
                    planks.add(holder);
                }
            }
        }
        nitro$woodCrafting = wood;
        nitro$planksCrafting = planks;
        nitro$cachedFor = manager;
        Nitro.LOGGER.info("Nitro: Hexerei woodcutter scan limited to {} wood-result crafting recipes (of {}) in {} ms",
            wood.size(), manager.getRecipes().size(), (System.nanoTime() - start) / 1_000_000L);
    }

    @Inject(method = "createRecipes", at = @At("RETURN"))
    private static void nitro$release(MinecraftServer server, CallbackInfo ci) {
        nitro$woodCrafting = null;
        nitro$planksCrafting = null;
        nitro$cachedFor = null;
    }

    @Redirect(
        method = "createRecipes",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipes()Ljava/util/Collection;")
    )
    private static Collection<RecipeHolder<?>> nitro$woodRecipesOnly(RecipeManager manager) {
        return manager == nitro$cachedFor && nitro$woodCrafting != null ? nitro$woodCrafting : manager.getRecipes();
    }

    @Redirect(
        method = "createRecipes",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipesFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;)Ljava/util/List;")
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <I extends RecipeInput, T extends Recipe<I>> List<RecipeHolder<T>> nitro$planksRecipesFor(RecipeManager manager, RecipeType<T> type, I input, Level level) {
        if (manager != nitro$cachedFor || nitro$planksCrafting == null || type != RecipeType.CRAFTING) {
            return manager.getRecipesFor(type, input, level);
        }
        List<RecipeHolder<T>> out = new ArrayList<>();
        for (RecipeHolder<?> holder : nitro$planksCrafting) {
            Recipe raw = holder.value();
            if (raw.matches(input, level)) {
                out.add((RecipeHolder<T>) holder);
            }
        }
        // vanilla getRecipesFor orders matches by result description id
        RegistryAccess access = level.registryAccess();
        out.sort(java.util.Comparator.comparing(h -> h.value().getResultItem(access).getDescriptionId()));
        return out;
    }
}

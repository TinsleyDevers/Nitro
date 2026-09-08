package com.tinsl.nitro.mixin.modernfix;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.client.ModelUniverse;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ModernFix constructs a {@code ModelBakeEventHelper} for the ModifyBakingResult event (on the
 * model-loading worker) and again for the BakingCompleted event (on the render thread, during
 * apply). Each construction generates the location of every block state and item model in the game
 * (4+ million), lists every item model file in every pack and walks the 4-million-key model
 * registry twice. Within one resource reload the inputs are identical, so the second construction
 * takes over the first one's sets (and only merges in model keys that were added in between).
 *
 * The reload generation (bumped at the start of every ModelManager reload) identifies "the same
 * reload". The sets are published through {@link ModelUniverse} for Puzzles Lib's passes too.
 */
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper", remap = false)
public abstract class ModelBakeEventHelperCacheMixin {
    @Shadow @Final @Mutable private Set<ModelResourceLocation> topLevelModelLocations;
    @Shadow @Final @Mutable private Set<String> namespacesWithModels;

    @Unique private boolean nitro$hit;

    /** First Set.forEach in the constructor: the walk over every block generating its state model locations. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V", ordinal = 0)
    )
    private void nitro$skipBlockWalkWhenCached(Set<Object> blockEntries, Consumer<Object> generate) {
        if (ModelUniverse.currentLocations() != null) {
            nitro$hit = true;
            return;
        }
        blockEntries.forEach(generate);
    }

    /** Second Set.forEach: "inventory" location of every registered item. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V", ordinal = 1)
    )
    private void nitro$skipItemWalkWhenCached(Set<Object> itemKeys, Consumer<Object> add) {
        if (!nitro$hit) {
            itemKeys.forEach(add);
        }
    }

    /** topLevelModelLocations.addAll(modelRegistry.keySet()): deferred to the merge at the end when cached. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;addAll(Ljava/util/Collection;)Z", ordinal = 0)
    )
    private boolean nitro$deferRegistryKeys(Set<Object> set, Collection<Object> keys) {
        return nitro$hit ? false : set.addAll(keys);
    }

    /** The "list every models/item json in every pack" step; redundant when cached. */
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/FileToIdConverter;listMatchingResources(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;")
    )
    private Map<ResourceLocation, Resource> nitro$skipItemListingWhenCached(FileToIdConverter converter, ResourceManager manager) {
        return nitro$hit ? Map.of() : converter.listMatchingResources(manager);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void nitro$storeOrRestore(Map<ModelResourceLocation, BakedModel> modelRegistry, CallbackInfo ci) {
        if (nitro$hit) {
            Set<ModelResourceLocation> cached = ModelUniverse.currentLocations();
            Set<String> cachedNamespaces = ModelUniverse.currentNamespaces();
            if (cached != null && cachedNamespaces != null) {
                // keys registered since the first event (e.g. remapped models) must stay visible
                cached.addAll(modelRegistry.keySet());
                cachedNamespaces.addAll(this.namespacesWithModels);
                this.topLevelModelLocations = cached;
                this.namespacesWithModels = cachedNamespaces;
                Nitro.LOGGER.info("Nitro: reused {} model locations from the earlier model event of this reload", cached.size());
                return;
            }
        }
        ModelUniverse.publish(this.topLevelModelLocations, this.namespacesWithModels);
    }
}

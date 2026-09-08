package com.tinsl.nitro.mixin.elysium;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Elysium API wraps every {@code getResource} of every pack to check for a "_condition.json"
 * sibling. It builds that sibling's {@link ResourceLocation} (with full path validation) once per
 * pack, and the resource manager asks up to ~1000 packs for the same location in a row. The
 * location is remembered per thread and reused while the same asset is being probed.
 */
@Mixin(targets = "net.jadenxgamer.elysium_api.impl.client.conditional_resource.ConditionalAssetsHelper", remap = false)
public abstract class ElysiumConditionCacheMixin {
    @Unique
    private static final ThreadLocal<Object[]> NITRO_LAST = ThreadLocal.withInitial(() -> new Object[2]);

    @Redirect(
        method = "isAssetHidden",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/resources/ResourceLocation;fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;")
    )
    private static ResourceLocation nitro$conditionLocation(String namespace, String path, PackResources pack, PackType type, ResourceLocation location) {
        Object[] memo = NITRO_LAST.get();
        if (memo[0] == location) {
            return (ResourceLocation) memo[1];
        }
        ResourceLocation result = ResourceLocation.fromNamespaceAndPath(namespace, path);
        memo[0] = location;
        memo[1] = result;
        return result;
    }
}

package com.tinsl.nitro.mixin.modernfix;

import com.google.common.base.Predicate;
import com.tinsl.nitro.client.NamespaceIndex;
import net.minecraft.client.resources.model.ModelResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * For a mod with SELF_AND_DEPS visibility ModernFix hands out {@code Sets.filter(universe, ...)}:
 * a view whose every iteration still walks all 4+ million locations and tests each one. A handler
 * that loops over the registry three times therefore costs ~4.5 s even though it only sees a few
 * hundred models. The view is replaced with a small materialised set built from a per-reload
 * namespace index (see {@link NamespaceIndex}); contents are identical.
 */
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper", remap = false)
public abstract class ModelVisibilityIndexMixin {
    @Shadow
    private Set<String> computeVisibleModIds(String modId) {
        throw new AssertionError();
    }

    @Redirect(
        method = "wrapRegistry",
        at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Sets;filter(Ljava/util/Set;Lcom/google/common/base/Predicate;)Ljava/util/Set;")
    )
    private Set<ModelResourceLocation> nitro$materialisedSubset(Set<ModelResourceLocation> universe, Predicate<ModelResourceLocation> predicate, String modId) {
        Set<String> visible = computeVisibleModIds(modId);
        return NamespaceIndex.subset(universe, visible);
    }
}

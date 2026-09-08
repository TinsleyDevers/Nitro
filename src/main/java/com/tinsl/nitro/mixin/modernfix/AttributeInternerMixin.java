package com.tinsl.nitro.mixin.modernfix;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ModernFix de-duplicates entity attribute templates with a hash set whose hash includes the base
 * value and whose equality compares base values with {@code ==}. Puffish Attributes reports
 * {@code NaN} as the base value of its dynamic attributes, and {@code NaN != NaN}: every such
 * template hashes to the same bucket and is never equal to any other, so each insert walks the
 * whole chain (quadratic; 4.5 s of the serial mod-setup phase on this pack).
 *
 * Templates with a NaN base value are hashed by identity instead. They were never de-duplicated
 * anyway, so the outcome is unchanged; only the cost is.
 */
@Mixin(targets = "org.embeddedt.modernfix.entity.AttributeInstanceTemplates$1", remap = false)
public abstract class AttributeInternerMixin {
    @Inject(method = "hashCode(Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;)I", at = @At("HEAD"), cancellable = true)
    private void nitro$identityHashForNaN(AttributeInstance instance, CallbackInfoReturnable<Integer> cir) {
        if (instance != null && Double.isNaN(instance.getBaseValue())) {
            cir.setReturnValue(System.identityHashCode(instance));
        }
    }
}

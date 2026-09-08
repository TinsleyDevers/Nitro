package com.tinsl.nitro.mixin.spawn;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Some mods query a mob's equipment while the mob is still inside {@code LivingEntity}'s
 * constructor (a synced-data callback fires from {@code setHealth}, and Forbidden Arcanus checks
 * held items from a {@code hasEffect} hook). At that point {@code Mob}'s equipment lists are not
 * assigned yet and the query throws a NullPointerException, so the mob never spawns. On this pack
 * that happens for the Spawn mod's clams on every spawn attempt: hundreds of failed spawns and
 * logged stack traces per minute of server time. During construction the mob holds nothing, so an
 * empty stack is the correct answer.
 */
@Mixin(Mob.class)
public abstract class MobEarlyEquipmentMixin {
    @Shadow @Final private NonNullList<ItemStack> handItems;
    @Shadow @Final private NonNullList<ItemStack> armorItems;

    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    private void nitro$emptyDuringConstruction(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        if (handItems == null || armorItems == null) {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}

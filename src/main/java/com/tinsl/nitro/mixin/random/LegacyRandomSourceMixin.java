package com.tinsl.nitro.mixin.random;

import com.tinsl.nitro.jei.AsyncJeiStarter;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * {@code Level.random} is a {@link LegacyRandomSource}, which throws "Accessing LegacyRandomSource
 * from multiple threads" when two threads touch it at once. Some JEI plugins (Apothic Enchanting,
 * for example) pull random registry entries while registering recipes. While that happens on the
 * Nitro JEI startup thread, give that thread its own random instead of racing the render thread.
 * This mirrors what C2ME's CheckedThreadLocalRandom does for world-gen threads.
 */
@Mixin(LegacyRandomSource.class)
public abstract class LegacyRandomSourceMixin {
    @Unique
    private static final ThreadLocal<SingleThreadedRandomSource> NITRO$FALLBACK =
        ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(new Random().nextLong()));

    @Inject(method = "next", at = @At("HEAD"), cancellable = true)
    private void nitro$nextOffThread(int bits, CallbackInfoReturnable<Integer> cir) {
        if (AsyncJeiStarter.isJeiStartupThread()) {
            cir.setReturnValue(NITRO$FALLBACK.get().next(bits));
        }
    }

    @Inject(method = "setSeed", at = @At("HEAD"), cancellable = true)
    private void nitro$setSeedOffThread(long seed, CallbackInfo ci) {
        if (AsyncJeiStarter.isJeiStartupThread()) {
            NITRO$FALLBACK.get().setSeed(seed);
            ci.cancel();
        }
    }
}

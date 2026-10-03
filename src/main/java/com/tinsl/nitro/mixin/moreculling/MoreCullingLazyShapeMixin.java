package com.tinsl.nitro.mixin.moreculling;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MoreCulling computes a per-block-state "face culling shape" from the state's baked model. It does
 * so eagerly for every block state (4.35 million here) in a resource reload listener, which asks
 * the model manager for every non-occluding state's model. Under ModernFix's dynamic resources that
 * single loop loads and bakes the majority of all block models on the render thread during the
 * apply phase, defeating the lazy loading and costing tens of seconds of launch.
 *
 * This mixin turns the cache lazy: the reload pass only marks states as stale, and the shape is
 * computed the first time a state's culling shape is actually requested (during chunk meshing,
 * where the model is loaded anyway). Same values, same code path, just on demand.
 *
 * Chunk meshing runs on several threads, and MoreCulling's computation stores a new, still empty
 * array in the field before it fills the six faces. Two threads computing the same state at once
 * could therefore hand one of them an empty face and crash the chunk builder, so the first use of a
 * state is computed by one thread at a time (per state; other states are not held up), and the
 * ready flag is only set once the array is complete.
 *
 * Applies after MoreCulling's own mixins (priority), which is what makes its merged methods
 * available as injection targets.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 1500)
public abstract class MoreCullingLazyShapeMixin {
    @Unique
    private static final ThreadLocal<Boolean> NITRO_COMPUTING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    @Unique
    private volatile boolean nitro$cullShapeReady;

    @Shadow(remap = false)
    public abstract void moreculling$initShapeCache();

    @Inject(method = "moreculling$initShapeCache", at = @At("HEAD"), cancellable = true, remap = false)
    private void nitro$deferShapeCache(CallbackInfo ci) {
        if (!NITRO_COMPUTING.get()) {
            // eager reload pass: only invalidate, compute on first use
            nitro$cullShapeReady = false;
            ci.cancel();
        }
    }

    @Inject(method = "moreculling$getFaceCullingShape", at = @At("HEAD"), remap = false)
    private void nitro$computeShapeOnDemand(Direction face, CallbackInfoReturnable<?> cir) {
        if (!nitro$cullShapeReady) {
            synchronized (this) {
                if (!nitro$cullShapeReady) {
                    NITRO_COMPUTING.set(Boolean.TRUE);
                    try {
                        moreculling$initShapeCache();
                    } finally {
                        NITRO_COMPUTING.set(Boolean.FALSE);
                    }
                    nitro$cullShapeReady = true;
                }
            }
        }
    }
}

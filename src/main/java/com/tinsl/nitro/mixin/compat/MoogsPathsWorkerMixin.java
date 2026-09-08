package com.tinsl.nitro.mixin.compat;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import net.neoforged.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Moog's Paths computes every path (an A* search where each step samples a full terrain-noise
 * column) on a fixed pool of {@code max(2, cores / 4)} background threads that run flat out for
 * minutes after a world opens, next to the chunk workers and the server thread. On a machine with
 * few cores that is a large share of the CPU the server thread needs. {@code pathfindThreads} in
 * Nitro's config caps the pool; paths are still generated, just later. Off (0) by default.
 */
@Mixin(targets = "com.finndog.moogs_paths.world.deferred.PathfindWorker", remap = false)
public abstract class MoogsPathsWorkerMixin {
    @ModifyVariable(method = "start", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Executors;newFixedThreadPool(ILjava/util/concurrent/ThreadFactory;)Ljava/util/concurrent/ExecutorService;"), ordinal = 0, remap = false)
    private static int nitro$capPathfindThreads(int cores) {
        int cap = NitroSettings.load(FMLPaths.GAMEDIR.get()).pathfindThreads;
        if (cap > 0 && cap < cores) {
            Nitro.LOGGER.info("Nitro: Moog's Paths pathfinding pool capped at {} thread(s) (mod default here: {})", cap, cores);
            return cap;
        }
        return cores;
    }
}

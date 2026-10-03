package com.tinsl.nitro.mixin.compat;

import com.tinsl.nitro.Nitro;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * WaterMedia 3 starts FFmpeg from its mod constructor: JavaCPP loads ten native libraries (93 MB,
 * each one checked by the antivirus as it loads), about 10 s here. FML moves on only when every mod
 * has been constructed, so on a large pack WaterMedia is the last one finished by about 6 s and the
 * whole launch waits for it.
 *
 * The load runs on its own thread instead. Nothing needs FFmpeg until a video plays, and everything
 * that could need it goes through four methods of MediaAPI: {@code createPlayer} (every overload),
 * {@code ffmpegLoaded}, {@code ffmpegError} and {@code release}. Each of those first waits for the
 * load to finish, so every answer WaterMedia gives is the one it would have given anyway.
 */
@Mixin(targets = "org.watermedia.api.media.MediaAPI", remap = false)
public abstract class WaterMediaFFmpegMixin {
    @Unique
    private static volatile CompletableFuture<Boolean> nitro$ffmpeg;
    @Unique
    private static volatile Thread nitro$loader;

    @Shadow
    private static boolean startFFmpeg() {
        throw new AssertionError();
    }

    @Redirect(method = "start", at = @At(value = "INVOKE", target = "Lorg/watermedia/api/media/MediaAPI;startFFmpeg()Z"), remap = false)
    private boolean nitro$startFFmpegInBackground() {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            long start = System.nanoTime();
            try {
                boolean loaded = startFFmpeg();
                done.complete(loaded);
                Nitro.LOGGER.info("Nitro: WaterMedia's FFmpeg {} on a background thread in {} ms",
                    loaded ? "loaded" : "did not load (see WaterMedia's lines above)", (System.nanoTime() - start) / 1_000_000L);
            } catch (Throwable t) {
                done.completeExceptionally(t);
                Nitro.LOGGER.warn("Nitro: WaterMedia's FFmpeg failed to start on the background thread", t);
            }
        }, "Nitro WaterMedia FFmpeg");
        thread.setDaemon(true);
        thread.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        nitro$loader = thread;
        nitro$ffmpeg = done;
        thread.start();
        // WaterMedia only uses the result to list FFmpeg among its startup failures; the background
        // thread logs a failure instead.
        return true;
    }

    @Unique
    private static void nitro$awaitFFmpeg() {
        CompletableFuture<Boolean> done = nitro$ffmpeg;
        if (done == null || done.isDone() || Thread.currentThread() == nitro$loader) {
            return;
        }
        long start = System.nanoTime();
        try {
            done.join();
        } catch (RuntimeException ignored) {
            // already logged by the loader thread; WaterMedia sees FFmpeg as not loaded, as it would have
        }
        Nitro.LOGGER.info("Nitro: waited {} ms for WaterMedia's FFmpeg on {}", (System.nanoTime() - start) / 1_000_000L,
            Thread.currentThread().getName());
    }

    @Inject(method = "createPlayer", at = @At("HEAD"), remap = false)
    private static void nitro$awaitBeforePlayer(CallbackInfoReturnable<Object> cir) {
        nitro$awaitFFmpeg();
    }

    @Inject(method = {"ffmpegLoaded", "ffmpegError"}, at = @At("HEAD"), remap = false)
    private static void nitro$awaitBeforeState(CallbackInfoReturnable<Boolean> cir) {
        nitro$awaitFFmpeg();
    }

    @Inject(method = "release", at = @At("HEAD"), remap = false)
    private void nitro$awaitBeforeRelease(CallbackInfo ci) {
        nitro$awaitFFmpeg();
    }
}

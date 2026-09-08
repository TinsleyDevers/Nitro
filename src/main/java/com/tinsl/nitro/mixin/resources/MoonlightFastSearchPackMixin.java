package com.tinsl.nitro.mixin.resources;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.resources.DirectoryPackCache;
import net.minecraft.FileUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Moonlight keeps generated data packs (Sawmill's ~29,000 recipe files here) in a folder on disk
 * and serves them through its own pack class, which opens every file individually on each data
 * pack reload. Same treatment as vanilla directory packs: read once into memory, serve from there.
 * Only SERVER_DATA packs are cached (the cache indexes the {@code data} tree).
 */
@Mixin(targets = "net.mehvahdjukaar.moonlight.api.resources.pack.FastSearchPathPackResources", remap = false)
public abstract class MoonlightFastSearchPackMixin {
    @Shadow @Final private Path root;
    @Shadow @Final private PackType packType;

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true)
    private void nitro$fromCache(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (type != this.packType || type != PackType.SERVER_DATA) {
            return;
        }
        DirectoryPackCache.Cache cache = DirectoryPackCache.get((PackResources) (Object) this, this.root);
        if (cache == null) {
            return;
        }
        byte[] bytes = cache.contents(type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath());
        if (bytes == null) {
            cir.setReturnValue(null);
        } else if (!cache.isTooLarge(bytes)) {
            cir.setReturnValue(() -> new ByteArrayInputStream(bytes));
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void nitro$listFromCache(PackType type, String namespace, String path, PackResources.ResourceOutput output, CallbackInfo ci) {
        if (type != this.packType || type != PackType.SERVER_DATA) {
            return;
        }
        DirectoryPackCache.Cache cache = DirectoryPackCache.get((PackResources) (Object) this, this.root);
        if (cache == null) {
            return;
        }
        String p = path;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.isEmpty() && (p.startsWith("/") || p.contains("//")
            || Arrays.stream(p.split("/", -1)).anyMatch(s -> !FileUtil.isValidStrictPathSegment(s)))) {
            return; // unusual path: let Moonlight handle it
        }
        String base = type.getDirectory() + "/" + namespace;
        String dir = p.isEmpty() ? base : base + "/" + p;
        if (cache.hasDirectory(dir)) {
            for (Map.Entry<String, byte[]> entry : cache.under(dir)) {
                String relative = entry.getKey().substring(base.length() + 1);
                ResourceLocation location = ResourceLocation.tryBuild(namespace, relative);
                if (location == null) {
                    Nitro.LOGGER.warn("Invalid path in pack: {}:{}, ignoring", namespace, relative);
                    continue;
                }
                byte[] bytes = entry.getValue();
                IoSupplier<InputStream> supplier = cache.isTooLarge(bytes)
                    ? IoSupplier.create(this.root.resolve(entry.getKey()))
                    : () -> new ByteArrayInputStream(bytes);
                output.accept(location, supplier);
            }
        }
        ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void nitro$release(CallbackInfo ci) {
        DirectoryPackCache.invalidate((PackResources) (Object) this);
    }
}

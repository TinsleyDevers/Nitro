package com.tinsl.nitro.mixin.resources;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.resources.DirectoryPackCache;
import com.tinsl.nitro.resources.PackIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Two things for pack file access:
 * <ul>
 *   <li>Misses are answered from an in-memory index instead of probing the (union/zip) file
 *       system, see {@link PackIndex}.</li>
 *   <li>Packs that are plain directories on disk are read once into memory and served from there,
 *       lookups and listings alike, see {@link DirectoryPackCache}.</li>
 * </ul>
 */
@Mixin(PathPackResources.class)
public abstract class PathPackResourcesMixin implements com.tinsl.nitro.resources.PackRoot {
    @Shadow @Final private Path root;

    @Override
    public Path nitro$root() {
        return this.root;
    }

    @Unique
    private static Boolean nitro$dirCacheEnabled;

    @Unique
    private static boolean nitro$dirCache() {
        Boolean b = nitro$dirCacheEnabled;
        if (b == null) {
            b = NitroSettings.load(FMLPaths.GAMEDIR.get()).directoryPackCache;
            nitro$dirCacheEnabled = b;
        }
        return b;
    }

    @Inject(
        method = "getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/server/packs/resources/IoSupplier;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void nitro$skipMissingResource(PackType type, ResourceLocation location, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        PathPackResources self = (PathPackResources) (Object) this;
        if (type == PackType.SERVER_DATA && nitro$dirCache()) {
            DirectoryPackCache.Cache cache = DirectoryPackCache.get(self, this.root);
            if (cache != null) {
                byte[] bytes = cache.contents(type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath());
                if (bytes == null) {
                    cir.setReturnValue(null);
                } else if (!cache.isTooLarge(bytes)) {
                    cir.setReturnValue(() -> new ByteArrayInputStream(bytes));
                }
                return; // too large: vanilla reads it from disk
            }
        }
        if (NitroSettings.load(FMLPaths.GAMEDIR.get()).resourceIndex && !PackIndex.mayContain(self, this.root, type, location)) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "listResources", at = @At("HEAD"), cancellable = true)
    private void nitro$listFromCache(PackType type, String namespace, String path, PackResources.ResourceOutput output, CallbackInfo ci) {
        if (type != PackType.SERVER_DATA || !nitro$dirCache()) {
            return;
        }
        DirectoryPackCache.Cache cache = DirectoryPackCache.get((PathPackResources) (Object) this, this.root);
        if (cache == null) {
            return;
        }
        String p = path;
        if (!p.isEmpty() && (p.startsWith("/") || p.endsWith("/") || p.contains("//")
            || java.util.Arrays.stream(p.split("/", -1)).anyMatch(s -> !net.minecraft.FileUtil.isValidStrictPathSegment(s)))) {
            return; // let vanilla validate and complain
        }
        String base = type.getDirectory() + "/" + namespace;
        String dir = p.isEmpty() ? base : base + "/" + p;
        if (cache.hasDirectory(dir)) {
            for (Map.Entry<String, byte[]> entry : cache.under(dir)) {
                String relative = entry.getKey().substring(base.length() + 1);
                ResourceLocation location = ResourceLocation.tryBuild(namespace, relative);
                if (location == null) {
                    Nitro.LOGGER.error("Invalid path in pack: {}:{}, ignoring", namespace, relative);
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

    @Inject(method = "close", at = @At("HEAD"))
    private void nitro$releaseCache(CallbackInfo ci) {
        DirectoryPackCache.invalidate((PathPackResources) (Object) this);
    }
}

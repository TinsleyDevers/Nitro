package com.tinsl.nitro.mixin.resources;

import com.tinsl.nitro.resources.DirectoryPackCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/**
 * When Moonlight regenerates a cached data pack it writes every generated file to disk and then
 * re-opens the folder as a pack. The bytes it just wrote are handed to {@link DirectoryPackCache}
 * so the in-memory copy does not have to read ~27,000 files back from disk moments later.
 */
@Mixin(targets = "net.mehvahdjukaar.moonlight.api.resources.pack.CachePathPackResources", remap = false)
public abstract class MoonlightCacheWriteMixin {
    @Inject(method = "addResource", at = @At("HEAD"))
    private void nitro$stageWrite(ResourceLocation id, byte[] bytes, CallbackInfo ci) {
        MoonlightCachedPackAccessor self = (MoonlightCachedPackAccessor) (Object) this;
        Path root = self.nitro$path();
        PackType type = self.nitro$packType();
        if (type == PackType.SERVER_DATA && root != null) {
            DirectoryPackCache.stage(root, type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath(), bytes);
        }
    }

    @Inject(method = "clearAllResources", at = @At("HEAD"))
    private void nitro$dropStaged(CallbackInfoReturnable<Boolean> cir) {
        DirectoryPackCache.unstage(((MoonlightCachedPackAccessor) (Object) this).nitro$path());
    }
}

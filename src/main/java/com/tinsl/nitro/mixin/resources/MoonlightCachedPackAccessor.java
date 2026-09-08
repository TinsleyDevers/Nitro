package com.tinsl.nitro.mixin.resources;

import net.minecraft.server.packs.PackType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(targets = "net.mehvahdjukaar.moonlight.api.resources.pack.AbstractCachedEditableResources", remap = false)
public interface MoonlightCachedPackAccessor {
    @Accessor("path")
    Path nitro$path();

    @Accessor("packType")
    PackType nitro$packType();
}

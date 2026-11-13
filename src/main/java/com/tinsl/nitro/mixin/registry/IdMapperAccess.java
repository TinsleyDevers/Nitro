package com.tinsl.nitro.mixin.registry;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.core.IdMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(IdMapper.class)
public interface IdMapperAccess {
    @Accessor("tToId")
    Reference2IntMap<?> nitro$ids();

    @Accessor("idToT")
    List<?> nitro$values();
}

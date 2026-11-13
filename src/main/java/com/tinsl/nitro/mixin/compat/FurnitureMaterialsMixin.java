package com.tinsl.nitro.mixin.compat;

import com.tinsl.nitro.Nitro;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Build furniture materials when a player uses them. The reload still clears the old map, so
 * no baked model or sprite is reused after a resource pack change.
 */
@Mixin(targets = "net.conczin.immersive_furniture.client.model.MaterialRegistry", remap = false)
public abstract class FurnitureMaterialsMixin {
    @Shadow
    public volatile Map<ResourceLocation, Object> materials;

    @Inject(method = "sync()V", at = @At("HEAD"), cancellable = true)
    private void nitro$deferMaterials(CallbackInfo callback) {
        try {
            Class<?> source = Class.forName("net.conczin.immersive_furniture.client.model.MaterialSource",
                false, getClass().getClassLoader());
            Method create = source.getMethod("create", BlockState.class);
            if (create.canAccess(null)) {
                materials = Collections.unmodifiableMap(new LazyMaterials(create));
                callback.cancel();
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            Nitro.LOGGER.debug("Nitro: falling back to the original furniture material reload", exception);
        }
    }

    // A separate class because the mixin itself is merged into the mod's module.
    public static final class LazyMaterials extends AbstractMap<ResourceLocation, Object> {
        private final Method create;
        private final Map<ResourceLocation, Optional<Object>> cache = new HashMap<>();
        private Map<ResourceLocation, Object> all;

        public LazyMaterials(Method create) {
            this.create = create;
        }

        @Override
        public synchronized Object get(Object key) {
            if (!(key instanceof ResourceLocation location)) {
                return null;
            }
            if (all != null) {
                return all.get(location);
            }
            return cache.computeIfAbsent(location, this::findMaterial).orElse(null);
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public synchronized Set<Entry<ResourceLocation, Object>> entrySet() {
            if (all == null) {
                Map<ResourceLocation, Object> found = new HashMap<>();
                for (ResourceLocation key : BuiltInRegistries.BLOCK.keySet()) {
                    Object material = get(key);
                    if (material != null) {
                        found.put(key, material);
                    }
                }
                all = Map.copyOf(found);
                cache.clear();
            }
            return all.entrySet();
        }

        private Optional<Object> findMaterial(ResourceLocation key) {
            if (!BuiltInRegistries.BLOCK.containsKey(key)) {
                return Optional.empty();
            }
            BlockState state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
            if (state.getRenderShape() != RenderShape.MODEL) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(create.invoke(null, state));
            } catch (InvocationTargetException exception) {
                // The original sync skips individual blocks whose models cannot be read.
                return Optional.empty();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot read furniture materials", exception);
            }
        }
    }
}

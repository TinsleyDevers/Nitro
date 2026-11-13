package com.tinsl.nitro.mixin.modernfix;

import com.google.common.collect.Lists;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identically shaped blocks can share variant names, even when their model IDs are different. */
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelLocationBuilder", remap = false)
public abstract class ModelVariantNamesMixin {
    @Unique private final Map<Property<?>, List<String>> nitro$options = new HashMap<>();
    @Unique private final Map<List<List<String>>, List<String>> nitro$variants = new HashMap<>();

    @Inject(method = "generateForBlock", at = @At("HEAD"), cancellable = true)
    private void nitro$reuseVariants(Set<ModelResourceLocation> models, Block block, ResourceLocation id,
                                     CallbackInfo callback) {
        var properties = block.defaultBlockState().getValues().keySet();
        List<List<String>> options = new ArrayList<>(properties.size());
        for (Property<?> property : properties) {
            options.add(nitro$options.computeIfAbsent(property, ModelVariantNamesMixin::nitro$formatOptions));
        }
        List<String> variants = nitro$variants.computeIfAbsent(options, ModelVariantNamesMixin::nitro$variantNames);
        for (String variant : variants) {
            models.add(new ModelResourceLocation(id, variant));
        }
        callback.cancel();
    }

    @Unique
    private static <T extends Comparable<T>> List<String> nitro$formatOptions(Property<T> property) {
        List<String> options = new ArrayList<>(property.getPossibleValues().size());
        for (T value : property.getPossibleValues()) {
            options.add(property.getName() + "=" + property.getName(value));
        }
        return options;
    }

    @Unique
    private static List<String> nitro$variantNames(List<List<String>> options) {
        List<List<String>> combinations = Lists.cartesianProduct(options);
        List<String> variants = new ArrayList<>(combinations.size());
        for (List<String> combination : combinations) {
            variants.add(String.join(",", combination));
        }
        return variants;
    }
}

package com.tinsl.nitro.mixin.relics;

import com.tinsl.nitro.Nitro;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Relics fills creative tabs by streaming the entire item registry (100k items here) and keeping
 * the ones implementing its content interface, for every creative tab that is built (~100 tabs per
 * rebuild, and tabs are rebuilt on every world join). The relic items are fixed once the registry
 * is frozen, so the filtered list is computed once and re-streamed.
 */
@Mixin(targets = "it.hurts.sskirillss.relics.init.RelicsCreativeTabs", remap = false)
public abstract class RelicsCreativeTabMixin {
    @Unique
    private static volatile List<Item> nitro$contentItems;

    @Redirect(
        method = "fillCreativeTabs",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Registry;stream()Ljava/util/stream/Stream;")
    )
    private static Stream<Item> nitro$relicItemsOnly(Registry<Item> registry) {
        List<Item> items = nitro$contentItems;
        if (items == null) {
            Class<?> marker;
            try {
                marker = Class.forName("it.hurts.sskirillss.relics.items.misc.ICreativeTabContent", false, RelicsCreativeTabMixin.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                return registry.stream(); // unknown layout: original behaviour
            }
            List<Item> found = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (marker.isInstance(item)) {
                    found.add(item);
                }
            }
            items = List.copyOf(found);
            nitro$contentItems = items;
            Nitro.LOGGER.info("Nitro: Relics creative tab handler now scans {} relic items instead of the whole item registry per tab", items.size());
        }
        return items.stream();
    }
}

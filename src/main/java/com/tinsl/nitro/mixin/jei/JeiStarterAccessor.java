package com.tinsl.nitro.mixin.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.library.config.ColorNameConfig;
import mezz.jei.library.config.ModIdFormatConfig;
import mezz.jei.library.config.RecipeCategorySortingConfig;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import mezz.jei.library.startup.IStopCallback;
import mezz.jei.library.startup.JeiStarter;
import mezz.jei.library.startup.StartData;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Only fields that JEI 19.53 through 19.57 all have. The config fields changed type in 19.55 and are
 * read through {@code JeiGeneration} instead, since an accessor for a field that is not there is a
 * mixin error.
 */
@Mixin(value = JeiStarter.class, remap = false)
public interface JeiStarterAccessor {
    @Accessor("running")
    boolean nitro$isRunning();

    @Accessor("running")
    void nitro$setRunning(boolean running);

    @Accessor("data")
    StartData nitro$getData();

    @Accessor("plugins")
    List<IModPlugin> nitro$getPlugins();

    @Accessor("vanillaPlugin")
    VanillaPlugin nitro$getVanillaPlugin();

    @Accessor("modIdFormatConfig")
    ModIdFormatConfig nitro$getModIdFormatConfig();

    @Accessor("colorNameConfig")
    ColorNameConfig nitro$getColorNameConfig();

    @Accessor("recipeCategorySortingConfig")
    RecipeCategorySortingConfig nitro$getRecipeCategorySortingConfig();

    @Accessor("stopCallbacks")
    List<IStopCallback> nitro$getStopCallbacks();

    @Invoker("verifyClientRecipes")
    void nitro$verifyClientRecipes(Minecraft minecraft);
}

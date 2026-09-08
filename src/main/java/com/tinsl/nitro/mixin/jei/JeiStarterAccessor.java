package com.tinsl.nitro.mixin.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.common.config.ConfigManager;
import mezz.jei.common.config.JeiClientConfigs;
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

    @Accessor("configManager")
    ConfigManager nitro$getConfigManager();

    @Accessor("jeiClientConfigs")
    JeiClientConfigs nitro$getJeiClientConfigs();

    @Accessor("stopCallbacks")
    List<IStopCallback> nitro$getStopCallbacks();

    @Invoker("verifyClientRecipes")
    void nitro$verifyClientRecipes(Minecraft minecraft);
}

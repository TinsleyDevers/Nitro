package com.tinsl.nitro.mixin;

import com.tinsl.nitro.common.NitroSettings;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Enables/disables individual mixins based on config and on which mods are present.
 */
public final class NitroMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private NitroSettings settings;
    private boolean jeiPresent;

    @Override
    public void onLoad(String mixinPackage) {
        settings = NitroSettings.load(FMLPaths.GAMEDIR.get());
        jeiPresent = modPresent("jei");
        if (jeiPresent && FMLLoader.getLoadingModList().getModFileById("jei").getMods().stream()
            .noneMatch(mod -> mod.getModId().equals("jei") && mod.getVersion().toString().equals("19.53.0.425"))) {
            LOGGER.warn("Nitro: async JEI disabled for an untested JEI version; this build targets 19.53.0.425");
            jeiPresent = false;
        }
    }

    private static boolean modPresent(String id) {
        return FMLLoader.getLoadingModList() != null && FMLLoader.getLoadingModList().getModFileById(id) != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".telemetry.")) return settings.startupReport;
        if (mixinClassName.contains(".jei.")) {
            boolean apply = jeiPresent && settings.asyncJei;
            if (!apply) {
                LOGGER.info("Nitro: skipping {} (jei present: {}, asyncJei: {})", mixinClassName, jeiPresent, settings.asyncJei);
            }
            return apply;
        }
        if (mixinClassName.endsWith("RecipeManagerMixin")) {
            return settings.parallelRecipes;
        }
        if (mixinClassName.endsWith("MoonlightFastSearchPackMixin") || mixinClassName.endsWith("MoonlightCachedPackAccessor")
            || mixinClassName.endsWith("MoonlightCacheWriteMixin")) {
            return modPresent("moonlight") && settings.directoryPackCache;
        }
        if (mixinClassName.contains(".resources.")) {
            return settings.resourceIndex || settings.directoryPackCache;
        }
        if (mixinClassName.contains(".puzzles.")) {
            return modPresent("puzzleslib");
        }
        if (mixinClassName.contains(".jaopca.")) {
            return modPresent("jaopca") && settings.jaopcaRemapFix;
        }
        if (mixinClassName.contains(".modernfix.")) {
            if (!modPresent("modernfix")) {
                return false;
            }
            if (mixinClassName.endsWith("ModelBakeEventHelperCacheMixin") || mixinClassName.endsWith("ModelManagerReloadMixin")) {
                return settings.modelLocationCache;
            }
            if (mixinClassName.endsWith("AttributeInternerMixin") || mixinClassName.endsWith("ModelVisibilityIndexMixin")) {
                return true;
            }
            return !settings.modelVisibility.isEmpty();
        }
        if (mixinClassName.contains(".spawn.")) {
            return settings.spawnFixes;
        }
        if (mixinClassName.contains(".sable.")) {
            return modPresent("sable") && settings.worldLoadFixes;
        }
        if (mixinClassName.contains(".megacells.")) {
            return modPresent("megacells") && settings.worldLoadFixes
                && FMLLoader.getLoadingModList().getModFileById("megacells").getMods().stream()
                    .anyMatch(mod -> mod.getModId().equals("megacells") && mod.getVersion().toString().equals("4.11.0"));
        }
        if (mixinClassName.contains(".hexerei.")) {
            return modPresent("hexerei") && settings.worldLoadFixes;
        }
        if (mixinClassName.contains(".farmersdelight.")) {
            return modPresent("farmersdelight") && settings.worldLoadFixes;
        }
        if (mixinClassName.contains(".relics.")) {
            return modPresent("relics") && settings.worldLoadFixes;
        }
        if (mixinClassName.contains(".compat.")) {
            if (mixinClassName.endsWith("CreateOreExcavationKubePluginMixin")) {
                return settings.compatFixes && modPresent("createoreexcavation") && modPresent("kubejs_create");
            }
            if (mixinClassName.endsWith("MoogsPathsWorkerMixin")) {
                return settings.pathfindThreads > 0 && modPresent("moogs_paths");
            }
            return settings.compatFixes;
        }
        if (mixinClassName.contains(".elysium.")) {
            return modPresent("elysium_api");
        }
        if (mixinClassName.contains(".moreculling.")) {
            return modPresent("moreculling") && settings.lazyCullingShapes;
        }
        if (mixinClassName.contains(".random.")) {
            if (!(jeiPresent && settings.asyncJei)) {
                return false;
            }
            if (mixinClassName.endsWith("C2meCheckedRandomMixin")) {
                return modPresent("c2me_fixes_worldgen_threading_issues");
            }
            return true;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

package com.tinsl.nitro.mixin;

import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.compat.ModTargets;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides which mixins are applied. Two things gate every fix that patches another mod: the config
 * switch it belongs to, and {@link ModTargets}, which checks that the mod is installed and that the
 * class Nitro patches still has the fields and methods the fix needs. Anything skipped says so in
 * the log and in the table Nitro prints when loading finishes.
 */
public final class NitroMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private NitroSettings settings;
    private boolean jeiActive;

    @Override
    public void onLoad(String mixinPackage) {
        settings = NitroSettings.load(FMLPaths.GAMEDIR.get());
        ModTargets.configure(settings.strictVersions, settings.jeiVersions);
        jeiActive = ModTargets.check("jei", settings.asyncJei).active();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".telemetry.")) {
            return settings.startupReport;
        }
        if (mixinClassName.contains(".jei.")) {
            return jeiActive;
        }
        if (mixinClassName.endsWith("RecipeManagerMixin")) {
            if (!settings.parallelRecipes) {
                return false;
            }
            String conflict = ModTargets.conflictFor("parallelRecipes");
            if (conflict != null) {
                ModTargets.record("parallelRecipes", ModTargets.State.OFF_CONFLICT, conflict + " handles recipes");
                LOGGER.info("Nitro: parallelRecipes is on in the config but {} is installed and already reworks the recipe "
                    + "pipeline, so Nitro leaves it alone.", conflict);
                return false;
            }
            return true;
        }
        if (mixinClassName.endsWith("MultiPackResourceManagerMixin")) {
            return settings.resourceIndex;
        }
        if (mixinClassName.endsWith("MoonlightFastSearchPackMixin")) {
            return ModTargets.check("moonlight.fastSearch", settings.directoryPackCache).active();
        }
        if (mixinClassName.endsWith("MoonlightCachedPackAccessor")) {
            return ModTargets.check("moonlight.cachedAccessor", settings.directoryPackCache).active();
        }
        if (mixinClassName.endsWith("MoonlightCacheWriteMixin")) {
            return ModTargets.check("moonlight.cacheWrite", settings.directoryPackCache).active();
        }
        if (mixinClassName.contains(".resources.")) {
            return settings.resourceIndex || settings.directoryPackCache;
        }
        if (mixinClassName.contains(".puzzles.")) {
            return ModTargets.check("puzzleslib", true).active();
        }
        if (mixinClassName.contains(".jaopca.")) {
            return ModTargets.check("jaopca", settings.jaopcaRemapFix).active();
        }
        if (mixinClassName.contains(".modernfix.")) {
            if (mixinClassName.endsWith("AttributeInternerMixin")) {
                return ModTargets.check("modernfix.attributes", true).active();
            }
            if (mixinClassName.endsWith("ModelBakeEventHelperCacheMixin") || mixinClassName.endsWith("ModelManagerReloadMixin")) {
                // ModelManagerReloadMixin targets vanilla but only makes sense with the cache mixin.
                return ModTargets.check("modernfix.modelCache", settings.modelLocationCache).active();
            }
            if (mixinClassName.endsWith("ModelVisibilityIndexMixin")) {
                return ModTargets.check("modernfix.visibilityIndex", true).active();
            }
            return ModTargets.check("modernfix.modelVisibility", !settings.modelVisibility.isEmpty()).active();
        }
        if (mixinClassName.contains(".spawn.")) {
            return settings.spawnFixes;
        }
        if (mixinClassName.contains(".sable.")) {
            return ModTargets.check("sable", settings.worldLoadFixes).active();
        }
        if (mixinClassName.contains(".megacells.")) {
            return ModTargets.check("megacells", settings.worldLoadFixes).active();
        }
        if (mixinClassName.contains(".hexerei.")) {
            return ModTargets.check("hexerei", settings.worldLoadFixes).active();
        }
        if (mixinClassName.contains(".farmersdelight.")) {
            return ModTargets.check("farmersdelight", settings.worldLoadFixes).active();
        }
        if (mixinClassName.contains(".relics.")) {
            return ModTargets.check("relics", settings.worldLoadFixes).active();
        }
        if (mixinClassName.contains(".compat.")) {
            if (mixinClassName.endsWith("CreateOreExcavationKubePluginMixin")) {
                return settings.compatFixes && ModTargets.present("kubejs_create")
                    && ModTargets.check("createoreexcavation", true).active();
            }
            if (mixinClassName.endsWith("MoogsPathsWorkerMixin")) {
                return ModTargets.check("moogs", settings.pathfindThreads > 0).active();
            }
            return settings.compatFixes;
        }
        if (mixinClassName.contains(".elysium.")) {
            return ModTargets.check("elysium", true).active();
        }
        if (mixinClassName.contains(".moreculling.")) {
            return ModTargets.check("moreculling", settings.lazyCullingShapes).active();
        }
        if (mixinClassName.contains(".random.")) {
            // Both exist only to keep the background JEI thread quiet.
            if (!jeiActive) {
                return false;
            }
            if (mixinClassName.endsWith("C2meCheckedRandomMixin")) {
                return ModTargets.check("c2me", true).active();
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

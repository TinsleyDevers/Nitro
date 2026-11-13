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

    /**
     * Mixin configs load right after the module layer that holds the mods is built, and Nitro's
     * launcher half marked the moment just before it. The difference is how long the module resolver
     * took on this launch: seconds on Java 24+ or with the agent, minutes on Java 21 without it. The
     * Java 25 notice quotes it.
     */
    private static void recordLayerPause() {
        String start = System.getProperty("nitro.layerStartNanos");
        if (start == null || System.getProperty("nitro.layerPauseMillis") != null) {
            return;
        }
        try {
            long millis = (System.nanoTime() - Long.parseLong(start)) / 1_000_000L;
            System.setProperty("nitro.layerPauseMillis", Long.toString(millis));
            LOGGER.info("Nitro: building the mod module layer took {} s", String.format("%.1f", millis / 1000.0));
        } catch (NumberFormatException ignored) {
            // not ours to worry about
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
        recordLayerPause();
        settings = NitroSettings.load(FMLPaths.GAMEDIR.get());
        ModTargets.configure(settings.strictVersions, settings.jeiVersions);
        String jeiRival = settings.asyncJei ? ModTargets.conflictFor("asyncJei") : null;
        if (jeiRival != null) {
            ModTargets.record("jei", ModTargets.State.OFF_CONFLICT, jeiRival + " already runs JEI's startup off the render thread");
            LOGGER.info("Nitro: async JEI left to {}, which takes over JEI's startup the same way. "
                + "Running both would have two mods replacing the same method.", jeiRival);
            jeiActive = false;
        } else {
            jeiActive = ModTargets.check("jei", settings.asyncJei).active()
                && ModTargets.check("jei.errorUtil", settings.asyncJei).active();
        }
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
        if (mixinClassName.contains(".registry.")) {
            return settings.compatFixes;
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
            // It reads Moonlight's fields through the accessor, so it can only apply when that did.
            return ModTargets.check("moonlight.cacheWrite", settings.directoryPackCache).active()
                && ModTargets.check("moonlight.cachedAccessor", settings.directoryPackCache).active();
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
            if (mixinClassName.endsWith("ModelVariantNamesMixin")) {
                return ModTargets.check("modernfix.variantNames", settings.modelLocationCache).active();
            }
            if (mixinClassName.endsWith("ModelVisibilityIndexMixin")) {
                return ModTargets.check("modernfix.visibilityIndex", true).active();
            }
            return ModTargets.check("modernfix.modelVisibility", !settings.modelVisibility.isEmpty()
                || settings.compatFixes).active();
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
            if (mixinClassName.endsWith("FurnitureMaterialsMixin")) {
                return ModTargets.check("furnitureMaterials", settings.compatFixes).active();
            }
            if (mixinClassName.endsWith("WaterMediaFFmpegMixin")) {
                return ModTargets.check("watermedia", settings.asyncWaterMedia).active();
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

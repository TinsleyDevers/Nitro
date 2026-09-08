package com.tinsl.nitro.launch;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Registers the embedded mod jar (META-INF/nitro/nitro-mod.jar) with FML.
 *
 * The launcher jar itself is claimed by ModLauncher's service-layer discovery, after which FML
 * refuses to treat the same file as a mod ("Skipping ... because it was already located earlier").
 * Shipping the mod as a nested jar and adding it here keeps the whole thing a single file in the
 * mods folder.
 */
public final class NitroDependencyLocator implements IDependencyLocator {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final String MOD_RESOURCE = "/META-INF/nitro/nitro-mod.jar";

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        try {
            Path modJar = NitroLaunchFiles.extract(FMLPaths.GAMEDIR.get(), MOD_RESOURCE, "nitro-mod");
            pipeline.addPath(modJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR)
                .ifPresentOrElse(
                    f -> LOGGER.info("Nitro: registered embedded mod jar {}", modJar.getFileName()),
                    () -> LOGGER.error("Nitro: FML did not accept the embedded mod jar {}", modJar));
        } catch (Exception e) {
            LOGGER.error("Nitro: failed to register the embedded mod jar", e);
        }
    }

    @Override
    public int getPriority() {
        return IDependencyLocator.DEFAULT_PRIORITY;
    }
}

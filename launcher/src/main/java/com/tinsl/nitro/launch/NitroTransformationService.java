package com.tinsl.nitro.launch;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Early hook. FML puts any mods-folder jar that provides an ITransformationService into the
 * ModLauncher SERVICE layer, whose services are initialised before the PLUGIN and GAME module
 * layers are resolved. That is the only point in time where the module-resolver patch can still
 * make a difference for the GAME layer (the layer that holds all ~1000 mod modules), and the
 * earliest point at which Mixin / Secure Jar Handler can be patched before they do their work.
 */
public final class NitroTransformationService implements ITransformationService {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private boolean envelopeCompatibility;

    @Override
    public @NotNull String name() {
        return "nitro";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        Path gameDir = env.getProperty(IEnvironment.Keys.GAMEDIR.get())
            .orElseGet(() -> Paths.get(".").toAbsolutePath().normalize());
        // This adapter is deliberately limited to the pair inspected and tested. Older JEI
        // versions still have the original injection point and must not be rewritten.
        envelopeCompatibility = java.nio.file.Files.isRegularFile(gameDir.resolve("mods/jei-1.21.1-neoforge-19.53.0.425.jar"))
            && java.nio.file.Files.isRegularFile(gameDir.resolve("mods/envelope-neoforge-1.21.1-0.7.5.jar"));
        try {
            NitroSettings settings = NitroSettings.load(gameDir);
            if (!settings.resolverPatch && !settings.loaderPatches) {
                LOGGER.info("Nitro: JVM agent disabled in config (resolverPatch=false, loaderPatches=false)");
                return;
            }
            if (!settings.resolverPatch && Runtime.version().feature() < 24) {
                LOGGER.info("Nitro: module resolver patch disabled in config; only loader patches will be applied");
            }
            System.setProperty("nitro.resolverPatch", String.valueOf(settings.resolverPatch));
            Instrumentation inst = AgentLoader.load(gameDir);
            if (inst != null && settings.loaderPatches) {
                LoaderPatches.apply(inst, gameDir, settings.classCache);
            }
        } catch (Throwable t) {
            LOGGER.warn("Nitro: early launch patches failed; continuing without them", t);
        }
    }

    @Override
    public void initialize(IEnvironment environment) {
        // nothing: all work happens in onLoad, which runs even earlier
    }

    @Override
    public @NotNull List<? extends ITransformer<?>> transformers() {
        return envelopeCompatibility ? List.of(new EnvelopeJeiCompatibility()) : List.of();
    }
}

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
        // The adapter itself only rewrites Envelope's callback when it still has the exact shape it
        // had on 0.7.5 and returns false otherwise, so the gate here only has to answer "are both
        // mods installed, on a JEI line the adapter was written for". Matching whole jar file names
        // was too strict: a JEI patch bump, or a launcher that renames jars, switched it off.
        envelopeCompatibility = hasJar(gameDir, "envelope-") && hasJar(gameDir, "jei-1.21.1-neoforge-19.53.");
        try {
            NitroSettings settings = NitroSettings.load(gameDir);
            reportConfigSources(settings);
            if (!settings.resolverPatch && !settings.loaderPatches) {
                LOGGER.info("Nitro: JVM agent disabled in config (resolverPatch=false, loaderPatches=false)");
                return;
            }
            if (!settings.resolverPatch && Runtime.version().feature() < 24) {
                LOGGER.info("Nitro: module resolver patch disabled in config; only loader patches will be applied");
            }
            Instrumentation inst = AgentLoader.load(gameDir);
            if (inst != null && settings.loaderPatches) {
                LoaderPatches.apply(inst, gameDir, settings.classCache);
            }
            if (inst != null && settings.startupProfiler && settings.startupReport) {
                nitro.agent.boot.NitroProfiler.start();
            }
        } catch (Throwable t) {
            LOGGER.warn("Nitro: early launch patches failed; continuing without them", t);
        }
    }

    /** @return true when the mods folder holds a jar whose name starts with {@code prefix}. */
    private static boolean hasJar(Path gameDir, String prefix) {
        Path mods = gameDir.resolve("mods");
        if (!java.nio.file.Files.isDirectory(mods)) {
            return false;
        }
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(mods)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".jar") && name.startsWith(prefix);
            });
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** Says out loud when a setting did not come from the config file, and flags keys we do not know. */
    private static void reportConfigSources(NitroSettings settings) {
        if (!settings.overriddenKeys.isEmpty()) {
            LOGGER.info("Nitro: config overridden on the command line: {}", String.join(", ", settings.overriddenKeys));
        }
        if (!settings.unknownKeys.isEmpty()) {
            LOGGER.info("Nitro: config/nitro.properties has keys this Nitro build does not use: {}. "
                + "They are left alone; delete them if they came from an older version.", String.join(", ", settings.unknownKeys));
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

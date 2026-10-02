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
        // The adapter only rewrites Envelope's callback when it still has the exact shape it had on
        // 0.7.5 and returns false otherwise, so the gate only has to answer "is Envelope installed, and
        // does the installed JEI have the moved injection point the adapter targets". That second part
        // is read out of JEI's own transfer handler rather than guessed from a file name: JEI 19.53
        // through at least 19.57 all have it, and a file-name check went quiet on every JEI update.
        envelopeCompatibility = hasJar(gameDir, "envelope-") && jeiHasMovedTransferHandler(gameDir);
        try {
            NitroSettings settings = NitroSettings.load(gameDir);
            reportConfigSources(settings);
            if (!settings.classCache) {
                removeClassCache(gameDir.resolve(".nitro").resolve("classcache"));
            }
            if (settings.startupProfiler && settings.startupReport) {
                // Started before anything else, and independent of the agent, so it covers the whole launch.
                StartupProfiler.start();
            }
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
        } catch (Throwable t) {
            LOGGER.warn("Nitro: early launch patches failed; continuing without them", t);
        }
    }

    private static final String JEI_TRANSFER_HANDLER = "mezz/jei/library/transfer/BasicRecipeTransferHandler.class";

    /**
     * True when the installed JEI's transfer handler has {@code transferRecipeInternal}, the method
     * JEI moved the packet sending into and which Envelope 0.7.5's mixin does not know about. Jars
     * named like JEI are looked at first; every jar only when none of those is JEI.
     */
    private static boolean jeiHasMovedTransferHandler(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!java.nio.file.Files.isDirectory(mods)) {
            return false;
        }
        java.util.List<Path> named = new java.util.ArrayList<>();
        java.util.List<Path> others = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(mods)) {
            files.filter(f -> f.getFileName().toString().endsWith(".jar"))
                .forEach(f -> (f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("jei") ? named : others).add(f));
        } catch (java.io.IOException e) {
            return false;
        }
        for (java.util.List<Path> group : java.util.List.of(named, others)) {
            for (Path jar : group) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                    java.util.zip.ZipEntry entry = zip.getEntry(JEI_TRANSFER_HANDLER);
                    if (entry == null) {
                        continue;
                    }
                    byte[] bytes;
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("transferRecipeInternal");
                } catch (java.io.IOException ignored) {
                    // unreadable jar; keep looking
                }
            }
        }
        return false;
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

    /**
     * A cache left behind by an earlier Nitro (where it was on by default) is deleted when the cache is
     * off: it is tens of megabytes, and turning the option back on later should start from a clean slate.
     */
    private static void removeClassCache(Path dir) {
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // held open by another running instance; it goes next time
                }
            });
            LOGGER.info("Nitro: removed the class cache left by an earlier version (it is now an experimental opt-in)");
        } catch (java.io.IOException e) {
            LOGGER.debug("Nitro: could not remove {}: {}", dir, e.toString());
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

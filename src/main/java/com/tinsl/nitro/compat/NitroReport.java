package com.tinsl.nitro.compat;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.compat.ModTargets.Decision;
import com.tinsl.nitro.compat.ModTargets.State;
import com.tinsl.nitro.resources.PackIndex;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One block in the log, once, when loading finishes: every fix Nitro ships, whether it is running,
 * and if not, why. A mod like this is only ever looked at when something is slow or broken, and the
 * first question in both cases is "is it even doing anything". Guessing that from scattered lines
 * across a 40 MB log is the thing this replaces.
 */
public final class NitroReport {
    private static boolean printed;

    private NitroReport() {}

    public static synchronized void print(NitroSettings settings, String version) {
        if (printed) {
            return;
        }
        printed = true;

        List<String> lines = new ArrayList<>();
        lines.add("Nitro " + version + " report");

        lines.add("  launch");
        lines.add(row("module resolver", resolverState()));
        String noAgent = "off, needs the agent in the JVM arguments";
        lines.add(row("loader patches", agentLoaded()
            ? (settings.loaderPatches ? "active" : "off in config")
            : noAgent));
        lines.add(row("class cache", !settings.classCache ? "off (experimental, see experimentalClassCache)"
            : agentLoaded() ? "ON (experimental)" : noAgent));
        String argument = System.getProperty("nitro.agentArgument");
        if (!agentLoaded() && argument != null) {
            lines.add(row("to turn these on", "add " + argument));
        }

        lines.add("  resources");
        long misses = PackIndex.missesSaved();
        lines.add(row("resource index", settings.resourceIndex
            ? "active, " + misses + " pack misses answered from memory, " + PackIndex.warmed() + " packs pre-indexed"
            : "off in config"));
        lines.add(row("directory pack cache", settings.directoryPackCache ? "active" : "off in config"));

        lines.add("  fixes");
        Map<String, Decision> decisions = ModTargets.decisions();
        for (Map.Entry<String, Decision> e : decisions.entrySet()) {
            Decision d = e.getValue();
            if (d.state() == State.OFF_ABSENT) {
                continue; // a mod that is not installed is noise, not information
            }
            String detail = d.detail() == null || d.detail().isBlank() ? "" : "  (" + d.detail() + ")";
            lines.add(row(e.getKey(), d.state().label + detail));
        }
        int absent = 0;
        for (Decision d : decisions.values()) {
            if (d.state() == State.OFF_ABSENT) {
                absent++;
            }
        }
        if (absent > 0) {
            lines.add(row("(not installed)", absent + " mod-specific fixes have nothing to attach to"));
        }
        lines.add(row("model visibility", settings.modelVisibility.isEmpty()
            ? "off in config" : "active for " + String.join(", ", settings.modelVisibility)));
        lines.add(row("spawn guard", settings.spawnFixes ? "active" : "off in config"));

        for (String line : profilerLines()) {
            lines.add("  " + line);
        }

        Nitro.LOGGER.info(String.join(System.lineSeparator(), lines));
    }

    private static String row(String name, String state) {
        return String.format("    %-24s %s", name, state);
    }

    private static String resolverState() {
        if (Boolean.getBoolean("nitro.resolverPatched")) {
            return "patched with the JDK 25 algorithm";
        }
        if (Runtime.version().feature() >= 24) {
            return "not needed, Java " + Runtime.version().feature() + " already has the fast one";
        }
        String error = System.getProperty("nitro.resolverPatchError");
        if (error != null) {
            return "NOT applied: " + error;
        }
        return agentLoaded() ? "off in config" : "off, needs the agent in the JVM arguments";
    }

    private static boolean agentLoaded() {
        return Boolean.getBoolean("nitro.agentLoaded");
    }

    /**
     * Asks the startup profiler in Nitro's launcher half for its summary. That half is loaded by
     * ModLauncher before the game layer exists, so it is found through the layer that holds it rather
     * than imported; when it cannot be found the report simply has no profile.
     */
    @SuppressWarnings("unchecked")
    private static List<String> profilerLines() {
        try {
            Class<?> profiler = launcherClass("com.tinsl.nitro.launch.StartupProfiler");
            if (profiler == null) {
                return List.of();
            }
            return (List<String>) profiler.getMethod("stop", Set.class).invoke(null, modModuleNames());
        } catch (Throwable t) {
            Nitro.LOGGER.debug("Nitro: startup profiler summary unavailable: {}", t.toString());
            return List.of();
        }
    }

    private static Class<?> launcherClass(String name) {
        try {
            return Class.forName(name, true, NitroReport.class.getClassLoader());
        } catch (Throwable ignored) {
            // not visible from the game layer; ask ModLauncher for the layer Nitro's launcher is in
        }
        try {
            var manager = cpw.mods.modlauncher.Launcher.INSTANCE.findLayerManager().orElse(null);
            if (manager == null) {
                return null;
            }
            var layer = manager.getLayer(cpw.mods.modlauncher.api.IModuleLayerManager.Layer.SERVICE).orElse(null);
            if (layer == null) {
                return null;
            }
            var module = layer.findModule("nitro.launch").orElse(null);
            return module == null ? null : Class.forName(module, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Names an installed mod can appear under in a stack trace: the module name FML gave its jar,
     * and the mod ids inside it. The profiler uses this only to say which of the things it timed are
     * mods the player could remove, so an incomplete set costs a label, not correctness.
     */
    private static Set<String> modModuleNames() {
        Set<String> names = new HashSet<>();
        // Nitro's own halves are separate modules from the mod jar, and Nitro is a mod.
        names.add("nitro.launch");
        names.add("nitro.agent");
        try {
            for (ModFileInfo file : FMLLoader.getLoadingModList().getModFiles()) {
                try {
                    names.add(file.moduleName());
                } catch (Throwable ignored) {
                    // some mod files have no module name; the mod ids below still cover them
                }
                for (IModInfo mod : file.getMods()) {
                    names.add(mod.getModId());
                }
            }
        } catch (Throwable t) {
            return Set.of();
        }
        return names;
    }
}

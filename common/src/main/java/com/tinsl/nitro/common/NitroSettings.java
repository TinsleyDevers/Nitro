package com.tinsl.nitro.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Nitro configuration. A plain properties file is used (config/nitro.properties) because part of
 * Nitro runs before FML's config infrastructure exists.
 *
 * This class is deliberately dependency free: it is compiled into both the early launcher jar
 * and the embedded mod jar. Keys that are missing from an existing file (older Nitro version) are
 * appended with their documentation and default, so upgrades pick up new features automatically.
 *
 * Any key can also be set on the command line with {@code -Dnitro.config.<key>=<value>}, which wins
 * over the file. That is meant for server operators who want one launch script to override a shared
 * config, and for bisecting a problem without editing the file.
 */
public final class NitroSettings {
    public static final String FILE_NAME = "nitro.properties";
    /** Prefix for the JVM system property form of every key. */
    public static final String OVERRIDE_PREFIX = "nitro.config.";

    /** Mods whose model-bake handlers were verified to only touch their own models; always safe to restrict. */
    public static final Set<String> BUILTIN_MODEL_VISIBILITY = Set.of("dragonlib", "vanillabackport", "goety", "alexsmobs");

    /** Hot-patch java.lang.module.Resolver (JDK 21 O(n^3) module graph bug). */
    public final boolean resolverPatch;
    /** Patch Mixin / SecureJarHandler hot spots through the JVM agent. */
    public final boolean loaderPatches;
    /** Run JEI's multi-minute startup on a background thread instead of the render thread. */
    public final boolean asyncJei;
    /** Thread priority for the JEI startup thread (1..10). */
    public final int asyncJeiPriority;
    /** Log a summary of startup / world-load phase timings. */
    public final boolean startupReport;
    /** Decode recipe JSON in parallel during data pack load. */
    public final boolean parallelRecipes;
    /** Answer resource-pack misses from an in-memory file index instead of probing the file system. */
    public final boolean resourceIndex;
    /** Apply Nitro's built-in list of mods that get restricted model visibility. */
    public final boolean modelVisibilityBuiltin;
    /** User-supplied mod ids whose ModifyBakingResult registry is restricted to their own + dependencies' models. */
    public final Set<String> modelVisibilityRestrict;
    /** Effective set: built-in list (if enabled) plus the user list. */
    public final Set<String> modelVisibility;
    /** Reuse ModernFix's per-reload model-location universe between the two model events of one reload. */
    public final boolean modelLocationCache;
    /** Replace JAOPCA's full model-registry walk with direct lookups. */
    public final boolean jaopcaRemapFix;
    /** Compute MoreCulling's per-block-state culling shapes on first use instead of for all states at reload. */
    public final boolean lazyCullingShapes;
    /** World-load fixes for specific mods' server-start / data-pack work (Sable, Hexerei, Farmer's Delight, Relics). */
    public final boolean worldLoadFixes;
    /** Guard against mods querying mob equipment during entity construction (failed spawns + log spam). */
    public final boolean spawnFixes;
    /** Read directory-backed packs (datapacks folder, KubeJS, Moonlight caches) into memory once per reload. */
    public final boolean directoryPackCache;
    /** Small load-order / compatibility fixes between specific mods (Create Ore Excavation + KubeJS Create). */
    public final boolean compatFixes;
    /** Keep transformed class bytes on disk (.nitro/classcache) and reuse them while mods and configs are unchanged. */
    public final boolean classCache;
    /** Cap for Moog's Paths' background path-finding threads; 0 keeps the mod's own max(2, cores/4). */
    public final int pathfindThreads;
    /** Only apply a mod-specific fix when that mod's version is one Nitro was tested against. */
    public final boolean strictVersions;
    /** Sample the loading threads during launch and report where the time went. */
    public final boolean startupProfiler;
    /** Version range of JEI that the asynchronous startup is applied to. */
    public final String jeiVersions;
    /** Keys found in the config file that Nitro does not know; reported once by the caller. */
    public final List<String> unknownKeys;
    /** Keys whose value came from a -Dnitro.config.* system property instead of the file. */
    public final List<String> overriddenKeys;

    private final List<String> overrides = new ArrayList<>();

    private NitroSettings(Properties p) {
        this.resolverPatch = bool(p, "resolverPatch", true);
        this.loaderPatches = bool(p, "loaderPatches", true);
        this.asyncJei = bool(p, "asyncJei", true);
        this.asyncJeiPriority = clamp(integer(p, "asyncJeiPriority", 3), 1, 10);
        this.startupReport = bool(p, "startupReport", true);
        this.parallelRecipes = bool(p, "parallelRecipes", false);
        this.resourceIndex = bool(p, "resourceIndex", true);
        this.modelVisibilityBuiltin = bool(p, "modelVisibilityBuiltin", true);
        this.modelVisibilityRestrict = list(p, "modelVisibilityRestrict", "dragonlib");
        Set<String> all = new LinkedHashSet<>();
        if (this.modelVisibilityBuiltin) {
            all.addAll(BUILTIN_MODEL_VISIBILITY);
        }
        all.addAll(this.modelVisibilityRestrict);
        this.modelVisibility = Collections.unmodifiableSet(all);
        this.modelLocationCache = bool(p, "modelLocationCache", true);
        this.jaopcaRemapFix = bool(p, "jaopcaRemapFix", true);
        this.lazyCullingShapes = bool(p, "lazyCullingShapes", true);
        this.worldLoadFixes = bool(p, "worldLoadFixes", true);
        this.spawnFixes = bool(p, "spawnFixes", true);
        this.directoryPackCache = bool(p, "directoryPackCache", true);
        this.compatFixes = bool(p, "compatFixes", true);
        this.classCache = bool(p, "classCache", true);
        this.pathfindThreads = integer(p, "pathfindThreads", 0);
        this.strictVersions = bool(p, "strictVersions", false);
        this.startupProfiler = bool(p, "startupProfiler", true);
        this.jeiVersions = string(p, "jeiVersions", "[19.53,19.54)");
        this.overriddenKeys = List.copyOf(overrides);
        this.unknownKeys = unknown(p);
    }

    /** Keys present in the file that no Nitro version in this build knows about. */
    private static List<String> unknown(Properties p) {
        Set<String> known = new LinkedHashSet<>();
        for (String[] e : ENTRIES) {
            known.add(e[0]);
        }
        Set<String> found = new TreeSet<>();
        for (String key : p.stringPropertyNames()) {
            if (!known.contains(key)) {
                found.add(key);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Value of a key, with {@code -Dnitro.config.<key>} winning over the file. Every override is
     * recorded so the caller can say which settings did not come from the file.
     */
    private String raw(Properties p, String key) {
        String override = System.getProperty(OVERRIDE_PREFIX + key);
        if (override != null && !override.isBlank()) {
            overrides.add(key + "=" + override.trim());
            return override.trim();
        }
        return p.getProperty(key);
    }

    private Set<String> list(Properties p, String key, String def) {
        String v = raw(p, key);
        if (v == null) {
            v = def;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : v.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private String string(Properties p, String key, String def) {
        String v = raw(p, key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static volatile NitroSettings cached;

    public static NitroSettings load(Path gameDir) {
        NitroSettings s = cached;
        if (s != null) {
            return s;
        }
        Properties p = new Properties();
        Path file = gameDir.resolve("config").resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
                appendMissing(file, p);
            } else {
                writeDefaults(file);
            }
        } catch (IOException ignored) {
            // fall through to defaults
        }
        s = new NitroSettings(p);
        cached = s;
        return s;
    }

    /** key, default, documentation - in file order. */
    private static final List<String[]> ENTRIES = List.of(
        new String[] {"resolverPatch", "true", """
            # Back-port the JDK 25 module-resolver fix into JDK 21 at launch. This removes the multi-minute
            # pause between mod discovery and the first Mixin log line ("Compatibility level set to ...").
            # Requires the game's Java to ship the jdk.attach module (the Mojang/Microsoft JDK does)."""},
        new String[] {"loaderPatches", "true", """
            # Patch two hot spots in the mod-loading toolchain itself through the same JVM agent:
            #  - Mixin 0.8.7 looks opcode names up with reflection for every instruction it processes (~5 CPU-seconds here).
            #  - SecureJarHandler probes all 1000+ jars for resources outside known packages (native libraries,
            #    META-INF/services, ...); a directory index narrows that to the jars that can contain them."""},
        new String[] {"asyncJei", "true", """
            # Move JEI's startup (recipe plugin registration + ingredient search index) off the render
            # thread. Fixes the multi-minute stutter/freeze after joining a world and the first-inventory-open hang."""},
        new String[] {"asyncJeiPriority", "3", """
            # Java thread priority for the JEI startup thread (1 = lowest, 10 = highest). Lower keeps FPS smooth."""},
        new String[] {"parallelRecipes", "false", """
            # EXPERIMENTAL: decode recipe JSON on all CPU cores during data pack load (world join).
            # Measured no gain on a 140k-recipe pack (codec decoding contends on shared registries), so off by default."""},
        new String[] {"resourceIndex", "true", """
            # Answer resource-pack lookups that would miss (most .mcmeta / metadata / conditional-asset probes)
            # from an in-memory file index instead of probing 1000+ jar file systems. Uses ModernFix's index when present."""},
        new String[] {"directoryPackCache", "true", """
            # Packs that are plain folders on disk (datapacks/, kubejs/, Moonlight's dynamic-*-pack-cache with ~30k Sawmill
            # recipe files here) are opened file by file on every data pack reload, and each open costs milliseconds when
            # antivirus inspects it (70 s of idle reload measured). Read each such pack into memory with all cores instead."""},
        new String[] {"modelVisibilityBuiltin", "true", """
            # ModernFix "dynamic resources" load models lazily, but a mod whose model-bake handler walks the whole
            # model registry forces every model to load on one thread. Nitro ships a list of mods whose handlers were
            # verified to only touch their own models (dragonlib, vanillabackport, goety, alexsmobs); with this on they
            # only see their own models and their dependencies' models in that event."""},
        new String[] {"modelVisibilityRestrict", "dragonlib", """
            # Additional mod ids (comma separated) to restrict the same way. Only add a mod if its model handler really
            # only modifies its own models; otherwise its changes to other mods' models would silently stop applying."""},
        new String[] {"modelLocationCache", "true", """
            # ModernFix rebuilds the list of every model location (4+ million here) twice per resource reload, once per
            # model event. Reuse the first result for the second event (saves seconds on the render thread)."""},
        new String[] {"jaopcaRemapFix", "true", """
            # JAOPCA remaps its generated block/item models by walking the entire model registry, which forces every
            # model to load. Look the remap targets up directly instead (same result, ~5 s less per reload)."""},
        new String[] {"lazyCullingShapes", "true", """
            # MoreCulling computes a culling shape for every block state (4+ million) at every resource reload, which
            # loads and bakes most block models on the render thread. Compute them on first use instead (same values)."""},
        new String[] {"worldLoadFixes", "true", """
            # World-load fixes for mods that redo large work at every server start / data pack load: Sable applies its
            # physics block properties once instead of once per dimension (46 here), Hexerei's woodcutter recipe scan only
            # looks at wood-result crafting recipes, Farmer's Delight tool-ability ingredients resolve once per ability,
            # Relics only scans its own items when creative tabs are built. Same results, seconds less per world join."""},
        new String[] {"spawnFixes", "true", """
            # A mob whose equipment is queried while it is still being constructed (Forbidden Arcanus hook + Spawn's clams
            # here) throws and never spawns; the server retries every tick and logs a stack trace each time. Answer such
            # early queries with an empty stack instead, which is what the mob actually holds at that moment."""},
        new String[] {"classCache", "true", """
            # Cache the result of the class transformer pipeline (Mixin, coremods, access transformers) on disk in
            # .nitro/classcache and reuse it on later launches. The cache is keyed on a fingerprint of the mod jars,
            # config files, launch arguments and versions, and every entry is checked against the untransformed class,
            # so a changed mod or config never serves stale code. Two launches in a row that do not reach the main menu
            # / "Done" discard it automatically. Delete the .nitro folder to reset it by hand."""},
        new String[] {"pathfindThreads", "0", """
            # Moog's Paths computes its paths on max(2, cores/4) background threads that run at full load for minutes after
            # a world opens (each path step samples a full terrain-noise column), competing with chunk generation and the
            # server thread. A positive value caps that pool (paths still generate, later); 0 keeps the mod's default.
            # Suggested on a dedicated server with few cores: 1."""},
        new String[] {"compatFixes", "true", """
            # Load-order fixes between specific mods. Create Ore Excavation decides whether to register its KubeJS wrapper
            # for Create's ProcessingOutput from a flag its mod constructor sets, but KubeJS may run the plugin first
            # (mods construct in parallel), registering the wrapper twice and failing KubeJS ("Wrapper ... already exists").
            # The flag is derived from the mod list right before it is read."""},
        new String[] {"startupReport", "true", """
            # Print a phase timing summary (main menu, world load, JEI) to the log, plus one table at the end of
            # loading that lists every Nitro fix and whether it is active, with the reason when it is not."""},
        new String[] {"strictVersions", "false", """
            # Every fix that patches another mod is checked twice: the target class must still have the fields and
            # methods the fix needs (a mismatch always skips the fix, which is what keeps Nitro from crashing on a
            # mod version it has not seen), and the mod's version is compared against the range Nitro was tested
            # against. With this off, a version outside that range still gets the fix as long as the class matches,
            # and Nitro logs that it is doing so. Turn it on to only ever run fixes on tested versions."""},
        new String[] {"startupProfiler", "true", """
            # Sample the loading threads a few times a second during launch and report which mods the time went to,
            # including time spent waiting on the network. Costs well under a percent of launch and tells you what is
            # slow that Nitro cannot fix. Reported once when loading completes, alongside the fix table."""},
        new String[] {"jeiVersions", "[19.53,19.54)", """
            # JEI version range the asynchronous startup applies to, as a maven range. The async startup re-implements
            # JEI's own start method, so it is tied to JEI's internals rather than to its public API. Widen this at your
            # own risk: if the re-implementation does not fit the installed JEI, Nitro falls back to JEI's normal
            # startup and logs it, so the worst case is the freeze you would have had anyway."""}
    );

    private static void writeDefaults(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Nitro - high mod-count performance fixes.\n");
        sb.append("# Every option is true/false unless stated otherwise. Delete this file to restore defaults.\n");
        for (String[] e : ENTRIES) {
            sb.append('\n').append(e[2]).append('\n').append(e[0]).append('=').append(e[1]).append('\n');
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void appendMissing(Path file, Properties p) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String[] e : ENTRIES) {
            if (!p.containsKey(e[0])) {
                sb.append('\n').append(e[2]).append('\n').append(e[0]).append('=').append(e[1]).append('\n');
            }
        }
        if (sb.length() > 0) {
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }

    private boolean bool(Properties p, String key, boolean def) {
        String v = raw(p, key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private int integer(Properties p, String key, int def) {
        String v = raw(p, key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package nitro.agent.boot;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Call targets for the loader-level patches Nitro installs through the JVM agent.
 *
 * This class lives on the bootstrap class path so that patched code in <em>any</em> module layer
 * (Mixin, Secure Jar Handler, ...) can call it. Every hook is written so that when nothing has been
 * registered it reproduces the original behaviour exactly.
 */
public final class NitroHooks {
    private NitroHooks() {}

    // ---------------------------------------------------------------------------------------------
    // Mixin: Bytecode.getOpcodeName(int, String, int)
    //
    // Mixin 0.8.7 builds an "activity" description for every single instruction of every mixin
    // method it applies, and the opcode name lookup it uses walks the fields of org.objectweb.asm.Opcodes
    // with reflection on each call. On a 1000-mod pack that is ~5 CPU-seconds of Field.getInt.
    // Same result, computed once per (start field, opcode).
    // ---------------------------------------------------------------------------------------------

    private static final ConcurrentHashMap<String, String[]> OPCODE_TABLES = new ConcurrentHashMap<>();

    public static String opcodeName(int opcode, String start, int min, Class<?> opcodesClass) {
        if (opcode >= min) {
            String[] table = OPCODE_TABLES.get(start);
            if (table == null) {
                table = buildTable(start, opcodesClass);
                OPCODE_TABLES.putIfAbsent(start, table);
            }
            if (opcode >= 0 && opcode < table.length) {
                String name = table[opcode];
                if (name != null) {
                    return name;
                }
            }
        }
        return opcode >= 0 ? String.valueOf(opcode) : "UNKNOWN";
    }

    /**
     * Mirrors Mixin's loop: starting at the field named {@code start}, the first int field whose
     * value equals the opcode wins. Values outside 0..511 are not tabulated (they fall back to the
     * numeric form, exactly like the original does when no field matches).
     */
    private static String[] buildTable(String start, Class<?> opcodesClass) {
        String[] table = new String[512];
        try {
            boolean found = false;
            for (Field f : opcodesClass.getDeclaredFields()) {
                if (!found && !f.getName().equals(start)) {
                    continue;
                }
                found = true;
                if (f.getType() == Integer.TYPE) {
                    int v = f.getInt(null);
                    if (v >= 0 && v < table.length && table[v] == null) {
                        table[v] = f.getName();
                    }
                }
            }
        } catch (Exception ignored) {
            // original swallows exceptions too and falls back to the numeric form
        }
        return table;
    }

    // ---------------------------------------------------------------------------------------------
    // Mixin: MixinProcessor.applyMixins(...) config scan
    //
    // For every class that is loaded Mixin asks every registered config (600+ here) whether the
    // class lies in its mixin package and whether it has mixins for it. A registered indexer
    // answers both questions from a precomputed map and hands back only the configs that can say
    // yes; the original loops then run over that short list. Without an indexer the full list is
    // returned, i.e. original behaviour.
    // ---------------------------------------------------------------------------------------------

    public interface ConfigIndexer {
        /** @return the configs (in original order) that may match {@code className}; null = unknown. */
        java.util.List<?> configsFor(java.util.List<?> configs, String className, long version);
    }

    public static volatile ConfigIndexer CONFIG_INDEXER;
    private static volatile long mixinConfigVersion;

    /** Called by the patched MixinConfig#prepare at return: a config's target set may have changed. */
    public static void mixinConfigsChanged() {
        mixinConfigVersion++;
    }

    public static java.util.List<?> mixinConfigsFor(java.util.List<?> configs, String className) {
        ConfigIndexer ix = CONFIG_INDEXER;
        if (ix == null) {
            return configs;
        }
        try {
            java.util.List<?> r = ix.configsFor(configs, className, mixinConfigVersion);
            return r != null ? r : configs;
        } catch (Throwable t) {
            return configs;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ModLauncher: ClassTransformer.transform(byte[], String, String)
    //
    // Every class the game loads goes through the transformer pipeline (launch plugins, Mixin,
    // coremods, access transformers, frame computation). With the same mods, configs and JVM
    // arguments the result is the same on every launch, so a registered cache can hand back last
    // launch's bytes for an unchanged input. Without a cache the original code runs unchanged.
    // ---------------------------------------------------------------------------------------------

    public interface ClassCache {
        /** @return cached transformed bytes for this exact input, or null to run the pipeline. */
        byte[] lookup(Object transformer, byte[] input, String className, String reason);

        /** Records the pipeline's result for this input. */
        void store(Object transformer, byte[] input, String className, String reason, byte[] result);

        /** The game reached a working state: this launch's cache contents are trustworthy. */
        void markClean();
    }

    public static volatile ClassCache CLASS_CACHE;

    public static byte[] classCacheLookup(Object transformer, byte[] input, String className, String reason) {
        ClassCache c = CLASS_CACHE;
        if (c == null || input == null || className == null) {
            return null;
        }
        try {
            return c.lookup(transformer, input, className, reason);
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] classCacheStore(byte[] result, Object transformer, byte[] input, String className, String reason) {
        ClassCache c = CLASS_CACHE;
        if (c != null && result != null && input != null && className != null) {
            try {
                c.store(transformer, input, className, reason, result);
            } catch (Throwable ignored) {
                // caching is best effort; the transformed class is returned regardless
            }
        }
        return result;
    }

    public static void classCacheMarkClean() {
        ClassCache c = CLASS_CACHE;
        if (c != null) {
            try {
                c.markClean();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Secure Jar Handler: ModuleClassLoader.findResourceList(String) slow path
    //
    // When a resource path does not map to a known package (native library folders such as
    // "windows-x86_64", META-INF/services, root files) SJH probes every module's jar file system.
    // A registered filter narrows that to the modules whose jar actually contains the directory.
    // ---------------------------------------------------------------------------------------------

    public interface ResourceFilter {
        /** @return the subset of {@code roots.values()} that may contain {@code name}; never null. */
        Collection<?> candidates(Object loader, Map<String, ?> roots, String name);
    }

    public static volatile ResourceFilter RESOURCE_FILTER;

    /**
     * Replaces {@code resolvedRoots.values()} in the slow path. Signature order matches the operand
     * stack at the patched call site (the map is already on the stack when the patch adds the
     * loader and the resource name).
     */
    public static Collection<?> resourceCandidates(Map<String, ?> roots, Object loader, String name) {
        ResourceFilter f = RESOURCE_FILTER;
        if (f == null) {
            return roots.values();
        }
        try {
            Collection<?> c = f.candidates(loader, roots, name);
            return c != null ? c : roots.values();
        } catch (Throwable t) {
            return roots.values();
        }
    }
}

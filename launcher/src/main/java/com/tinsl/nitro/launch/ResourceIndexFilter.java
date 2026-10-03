package com.tinsl.nitro.launch;

import cpw.mods.niofs.union.UnionFileSystem;
import nitro.agent.boot.NitroHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.module.ModuleReference;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Directory index for Secure Jar Handler's slow resource path (see {@link LoaderPatches}).
 *
 * For each {@code ModuleClassLoader} the index is built once, lazily, on the first lookup that
 * falls outside a known package: every module's jar central directory is read (in parallel) and
 * the set of directories it contains is recorded as 64-bit hashes. A lookup then only visits the
 * modules whose jar has the requested directory. Hash collisions can only add a module to the
 * candidate list (an extra probe), never remove one, so the result is always a superset of the
 * modules that hold the resource. A module that cannot be listed is simply probed on every lookup,
 * as before.
 */
final class ResourceIndexFilter implements NitroHooks.ResourceFilter {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final int MODULE_BITS = 11;              // up to 2047 modules per loader
    private static final long MODULE_MASK = (1L << MODULE_BITS) - 1;

    private final Map<Object, Index> indexes = new IdentityHashMap<>();
    private static Method getBasePaths; // UnionFileSystem#getBasePaths (package-private, opened via Instrumentation)

    @Override
    public Collection<?> candidates(Object loader, Map<String, ?> roots, String name) {
        Index index;
        synchronized (this) {
            index = indexes.get(loader);
            if (index == null) {
                index = Index.build(loader, roots);
                indexes.put(loader, index);
            }
        }
        if (index.keys == null || !plainRelative(name)) {
            return roots.values();
        }
        return index.candidates(name);
    }

    /**
     * The index is keyed by the jars' own entry names ("assets/x"). A name in any other form
     * ("/assets/x/y.json", "./a", "a/../b", backslashes) is one Secure Jar Handler may still resolve,
     * so those are searched in every jar exactly as without Nitro.
     */
    static boolean plainRelative(String name) {
        return !name.startsWith("/") && !name.startsWith("./") && !name.contains("//") && !name.contains("/./")
            && !name.contains("..") && name.indexOf('\\') < 0;
    }

    private static final class Index {
        static final Index FAILED = new Index(null, null, List.of());

        final long[] keys;          // sorted; high bits = directory hash, low MODULE_BITS = module index
        final Object[] modules;
        final List<Object> alwaysInclude; // modules that could not be indexed

        private Index(long[] keys, Object[] modules, List<Object> alwaysInclude) {
            this.keys = keys;
            this.modules = modules;
            this.alwaysInclude = alwaysInclude;
        }

        Collection<?> candidates(String name) {
            // directory part of the resource name; "" for root entries
            int end = name.endsWith("/") ? name.length() - 1 : name.length();
            int slash = name.lastIndexOf('/', end - 1);
            String dir = slash < 0 ? "" : name.substring(0, slash);
            long prefix = hash(dir) & ~MODULE_MASK;
            int i = Arrays.binarySearch(keys, prefix);
            if (i < 0) {
                i = -i - 1;
            }
            List<Object> out = new ArrayList<>(alwaysInclude);
            while (i < keys.length && (keys[i] & ~MODULE_MASK) == prefix) {
                out.add(modules[(int) (keys[i] & MODULE_MASK)]);
                i++;
            }
            return out;
        }

        static Index build(Object loader, Map<String, ?> roots) {
            long start = System.nanoTime();
            Object[] modules = roots.values().toArray();
            if (modules.length >= (1 << MODULE_BITS)) {
                LOGGER.warn("Nitro: {} modules in one layer exceeds the resource index capacity; using full scans", modules.length);
                return FAILED;
            }
            AtomicReference<String> firstFailure = new AtomicReference<>();
            long[][] parts;
            try {
                parts = IntStream.range(0, modules.length).parallel()
                    .mapToObj(i -> directoriesOf(modules[i], i, firstFailure))
                    .toArray(long[][]::new);
            } catch (Throwable t) {
                LOGGER.warn("Nitro: resource index build failed; using full scans", t);
                return FAILED;
            }
            int total = 0;
            List<Object> unindexed = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] == null) {
                    unindexed.add(modules[i]);
                } else {
                    total += parts[i].length;
                }
            }
            long[] keys = new long[total];
            int pos = 0;
            for (long[] p : parts) {
                if (p != null) {
                    System.arraycopy(p, 0, keys, pos, p.length);
                    pos += p.length;
                }
            }
            Arrays.sort(keys);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            LOGGER.info("Nitro: indexed {} directories across {} modules in {} ms for resource lookups outside known packages ({})",
                total, modules.length - unindexed.size(), ms, loader instanceof ClassLoader cl ? cl.getName() : loader);
            if (!unindexed.isEmpty()) {
                LOGGER.warn("Nitro: {} module(s) could not be indexed and will be probed on every lookup; first failure: {}",
                    unindexed.size(), firstFailure.get());
            }
            return new Index(keys, modules, List.copyOf(unindexed));
        }

        /** @return hashes of every directory in the module's jar(s), tagged with the module index; null on failure. */
        private static long[] directoriesOf(Object ref, int moduleIndex, AtomicReference<String> firstFailure) {
            String stage = "module reference";
            try {
                if (!(ref instanceof ModuleReference mr)) {
                    throw new IllegalStateException("not a ModuleReference: " + ref.getClass().getName());
                }
                stage = "location";
                URI uri = mr.location().orElseThrow(() -> new IllegalStateException("module has no location"));
                stage = "path of " + uri;
                Path root = Path.of(uri);
                Set<String> dirs = new HashSet<>(256);
                dirs.add("");
                stage = "base paths of " + uri;
                List<Path> bases = basePaths(root);
                if (bases != null) {
                    boolean walkedUnion = false;
                    for (Path base : bases) {
                        stage = "listing " + base;
                        boolean onDisk = base.getFileSystem() == FileSystems.getDefault();
                        if (onDisk && Files.isRegularFile(base)) {
                            listZip(base, dirs);
                        } else if (onDisk && Files.isDirectory(base)) {
                            walk(base, dirs);
                        } else if (!walkedUnion) {
                            // nested jar (jar-in-jar) or other virtual file system: list through the union view
                            stage = "walking " + uri;
                            walk(root, dirs);
                            walkedUnion = true;
                        }
                    }
                } else {
                    stage = "walking " + uri;
                    walk(root, dirs);
                }
                long[] out = new long[dirs.size()];
                int i = 0;
                for (String d : dirs) {
                    out[i++] = (hash(d) & ~MODULE_MASK) | moduleIndex;
                }
                return out;
            } catch (Throwable t) {
                firstFailure.compareAndSet(null, stage + ": " + t);
                return null;
            }
        }

        private static List<Path> basePaths(Path root) {
            FileSystem fs = root.getFileSystem();
            if (!(fs instanceof UnionFileSystem ufs)) {
                return null;
            }
            try {
                Method m = getBasePaths;
                if (m == null) {
                    m = UnionFileSystem.class.getDeclaredMethod("getBasePaths");
                    m.setAccessible(true);
                    getBasePaths = m;
                }
                @SuppressWarnings("unchecked")
                List<Path> bases = (List<Path>) m.invoke(ufs);
                return bases;
            } catch (Throwable t) {
                return null; // fall back to walking the union file system itself
            }
        }

        private static void listZip(Path jar, Set<String> dirs) throws IOException {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    addParents(en.nextElement().getName(), dirs);
                }
            }
        }

        private static void walk(Path base, Set<String> dirs) throws IOException {
            try (Stream<Path> s = Files.walk(base)) {
                s.forEach(p -> {
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    if (!rel.isEmpty()) {
                        addParents(Files.isDirectory(p) ? rel + "/" : rel, dirs);
                    }
                });
            }
        }

        /** Adds every directory prefix of an entry name (and, for multi-release entries, the un-versioned prefixes). */
        private static void addParents(String entry, Set<String> dirs) {
            int end = entry.endsWith("/") ? entry.length() - 1 : entry.lastIndexOf('/');
            for (int i = end; i > 0; i = entry.lastIndexOf('/', i - 1)) {
                if (!dirs.add(entry.substring(0, i))) {
                    break; // all shorter prefixes are already present
                }
            }
            if (entry.startsWith("META-INF/versions/")) {
                int slash = entry.indexOf('/', "META-INF/versions/".length());
                if (slash > 0 && slash + 1 < entry.length()) {
                    addParents(entry.substring(slash + 1), dirs);
                }
            }
        }
    }

    /** FNV-1a, 64 bit. */
    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0, n = s.length(); i < n; i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}

package com.tinsl.nitro.resources;

import com.tinsl.nitro.Nitro;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Answers "does this pack contain this file?" without touching the file system.
 *
 * With ~1200 packs (every mod jar is one) a miss in {@code PathPackResources.getResource} costs a
 * path resolve plus an existence probe through the union/zip file system, and the resource manager
 * asks every pack that owns the namespace. Sprite metadata (.mcmeta), CTM texture metadata,
 * Elysium's conditional-asset probe, Entity Model Features' optifine/emf directory probes and
 * similar lookups miss for the vast majority of the ~100k textures and models, so at startup the
 * misses outnumber the hits by a wide margin.
 *
 * Two levels: a per-(pack, namespace) set of top-level directory names rejects most misses with one
 * hash lookup (a probe for {@code optifine/...} in a pack that has no {@code optifine} folder), and
 * the full per-file check uses ModernFix's in-memory tree of every file in each pack when it is
 * present, or a tree Nitro walks once otherwise.
 */
public final class PackIndex {
    private static final Map<PathPackResources, Index> INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong MISSES_SAVED = new AtomicLong();
    private static volatile boolean loggedStats;

    // ModernFix integration (resolved reflectively so Nitro does not depend on it).
    private static volatile boolean modernfixProbed;
    private static Field engineField;
    private static MethodHandle hasResource;
    private static MethodHandle decompose;

    private PackIndex() {}

    /** @return false if the pack definitely does not contain the resource. */
    public static boolean mayContain(PathPackResources pack, Path root, PackType type, ResourceLocation location) {
        // Directory packs may be regenerated in place. Only the reload-scoped directory cache
        // may answer their misses; the permanent jar index must never hide new disk files.
        if (root == null || root.getFileSystem() == java.nio.file.FileSystems.getDefault()) return true;
        Index index = INDEXES.get(pack);
        if (index == null) {
            synchronized (pack) {
                index = INDEXES.get(pack);
                if (index == null) {
                    index = create(pack, root);
                    INDEXES.put(pack, index);
                }
            }
        }
        if (index == Index.UNKNOWN) {
            return true;
        }
        String path = location.getPath();
        boolean present;
        Set<String> children = index.children(type.getDirectory(), location.getNamespace());
        if (children != null) {
            int slash = path.indexOf('/');
            present = children.contains(slash < 0 ? path : path.substring(0, slash));
        } else {
            present = true;
        }
        if (present) {
            present = index.contains(type.getDirectory() + "/" + location.getNamespace() + "/" + path);
        }
        if (!present) {
            long n = MISSES_SAVED.incrementAndGet();
            if (!loggedStats && n == 200_000) {
                loggedStats = true;
                Nitro.LOGGER.info("Nitro: resource index has short-circuited {} resource misses so far", n);
            }
        }
        return present;
    }

    public static long missesSaved() {
        return MISSES_SAVED.get();
    }

    private static Index create(PathPackResources pack, Path root) {
        if (root == null) {
            return Index.UNKNOWN;
        }
        Contains full = modernfixIndex(pack);
        if (full == null) {
            full = walk(root);
        }
        if (full == null) {
            return Index.UNKNOWN;
        }
        return new Index(root, full);
    }

    private static Contains modernfixIndex(PathPackResources pack) {
        if (!modernfixProbed) {
            synchronized (PackIndex.class) {
                if (!modernfixProbed) {
                    probeModernFix(pack.getClass());
                    modernfixProbed = true;
                }
            }
        }
        if (engineField == null) {
            return null;
        }
        try {
            Object engine = engineField.get(pack);
            if (engine == null) {
                return null;
            }
            MethodHandle has = hasResource.bindTo(engine);
            return relative -> {
                try {
                    String[] parts = (String[]) decompose.invoke(relative);
                    return (boolean) has.invoke(parts);
                } catch (Throwable t) {
                    return true;
                }
            };
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void probeModernFix(Class<?> packClass) {
        try {
            Class<?> engineClass = Class.forName("org.embeddedt.modernfix.resources.PackResourcesCacheEngine", false, packClass.getClassLoader());
            Field found = null;
            for (Class<?> c = packClass; c != null && found == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == engineClass) {
                        f.setAccessible(true);
                        found = f;
                        break;
                    }
                }
            }
            if (found == null) {
                Nitro.LOGGER.info("Nitro: ModernFix present but no pack cache engine field found; using own resource index");
                return;
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle has = lookup.findVirtual(engineClass, "hasResource", MethodType.methodType(boolean.class, String[].class));
            MethodHandle dec = lookup.findStatic(engineClass, "decomposeCached", MethodType.methodType(String[].class, String.class));
            engineField = found;
            hasResource = has;
            decompose = dec;
            Nitro.LOGGER.info("Nitro: reusing ModernFix's pack file index for resource existence checks");
        } catch (ClassNotFoundException e) {
            Nitro.LOGGER.info("Nitro: ModernFix not present; building own resource index per pack");
        } catch (Throwable t) {
            Nitro.LOGGER.warn("Nitro: could not bind to ModernFix's pack index, using own", t);
        }
    }

    private static Contains walk(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        Set<String> files = new HashSet<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = root.relativize(p).toString();
                if (rel.indexOf('\\') >= 0) {
                    rel = rel.replace('\\', '/');
                }
                files.add(rel);
            });
        } catch (IOException | RuntimeException e) {
            return null;
        }
        Set<String> frozen = Set.copyOf(files);
        return frozen::contains;
    }

    @FunctionalInterface
    private interface Contains {
        boolean contains(String relative);
    }

    private static final class Index {
        static final Index UNKNOWN = new Index(null, relative -> true);
        private static final Set<String> NONE = Set.of();

        private final Path root;
        private final Contains full;
        /** "assets/namespace" -> names of the entries directly inside that directory. */
        private final ConcurrentHashMap<String, Set<String>> children = new ConcurrentHashMap<>();

        Index(Path root, Contains full) {
            this.root = root;
            this.full = full;
        }

        boolean contains(String relative) {
            return full.contains(relative);
        }

        /** @return the names directly under {@code <typeDir>/<namespace>} in this pack, or null if unknown. */
        Set<String> children(String typeDir, String namespace) {
            String key = typeDir + "/" + namespace;
            Set<String> set = children.get(key);
            if (set == null) {
                set = listChildren(typeDir, namespace);
                children.put(key, set);
            }
            return set == NONE_UNKNOWN ? null : set;
        }

        private static final Set<String> NONE_UNKNOWN = Collections.unmodifiableSet(new HashSet<>());

        private Set<String> listChildren(String typeDir, String namespace) {
            try {
                Path dir = root.resolve(typeDir).resolve(namespace);
                if (!Files.isDirectory(dir)) {
                    return NONE;
                }
                Set<String> names = new HashSet<>();
                try (Stream<Path> s = Files.list(dir)) {
                    s.forEach(p -> {
                        String n = p.getFileName().toString();
                        if (n.endsWith("/")) {
                            n = n.substring(0, n.length() - 1);
                        }
                        names.add(n);
                    });
                }
                return names.isEmpty() ? NONE : Set.copyOf(names);
            } catch (IOException | RuntimeException e) {
                return NONE_UNKNOWN;
            }
        }
    }
}

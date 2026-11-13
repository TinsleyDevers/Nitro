package com.tinsl.nitro.resources;

import com.tinsl.nitro.Nitro;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.FileUtil;
import java.io.InputStream;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
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
    private static final LongAdder MISSES_SAVED = new LongAdder();

    // ModernFix integration (resolved reflectively so Nitro does not depend on it).
    private static volatile boolean modernfixProbed;
    private static Field engineField;
    private static MethodHandle hasResource;
    private static MethodHandle decompose;
    private static MethodHandle collectResources;

    private PackIndex() {}

    /** Build the per-pack index once, shared by reload warmup and lookups. */
    public static Index index(PathPackResources pack, PackRoot holder) {
        Index index = holder.nitro$index();
        if (index == null) {
            synchronized (pack) {
                index = holder.nitro$index();
                if (index == null) {
                    Path root = holder.nitro$root();
                    index = isJarPack(root) ? create(pack, root) : Index.UNKNOWN;
                    holder.nitro$setIndex(index);
                }
            }
        }
        return index;
    }

    public static long missesSaved() {
        return MISSES_SAVED.sum();
    }

    private static boolean isJarPack(Path root) {
        return root != null && root.getFileSystem() != java.nio.file.FileSystems.getDefault();
    }

    /**
     * Builds the indexes for a whole resource manager's packs up front, on several threads.
     *
     * Without ModernFix each index costs one walk of the pack's zip file system, and building them
     * lazily means the first miss in each of a thousand packs pays for that walk on the thread that
     * is doing the reload, one pack after another. The same work spread over the idle cores while
     * the reload is getting started is off the critical path entirely. Nothing here changes what the
     * index answers: a pack that is not warmed in time is still built on demand, under the same
     * lock, exactly as before.
     */
    public static void warm(PackType type, List<PackResources> packs) {
        List<PathPackResources> pending = new ArrayList<>();
        for (PackResources pack : packs) {
            if (pack instanceof PathPackResources path && pack instanceof PackRoot holder
                && holder.nitro$index() == null && isJarPack(holder.nitro$root())) {
                pending.add(path);
            }
        }
        if (pending.size() < 8) {
            return; // not enough to be worth a thread pool
        }
        int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        AtomicInteger seq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Nitro pack index " + seq.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        for (PathPackResources pack : pending) {
            pool.execute(() -> {
                try {
                    index(pack, (PackRoot) pack);
                } catch (Throwable ignored) {
                    // the on-demand path will build it instead
                }
            });
        }
        pool.shutdown();
        Nitro.LOGGER.debug("Nitro: warming {} {} pack indexes on {} threads", pending.size(), type, threads);
        WARMED.add(pending.size());
    }

    private static final LongAdder WARMED = new LongAdder();

    public static long warmed() {
        return WARMED.sum();
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
            return query -> {
                try {
                    return (boolean) has.invokeExact(query.components());
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
            try {
                collectResources = lookup.findVirtual(engineClass, "collectResources", MethodType.methodType(void.class,
                    PackType.class, String.class, String[].class, int.class, PackResources.ResourceOutput.class))
                    .asType(MethodType.methodType(void.class, Object.class, PackType.class, String.class,
                        String[].class, int.class, PackResources.ResourceOutput.class));
            } catch (ReflectiveOperationException ignored) {
                // Older cache engines can still answer individual file lookups.
            }
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

    /**
     * List from the same immutable file tree ModernFix already builds for jar packs.
     * Directories and older ModernFix versions use the original implementation.
     */
    public static boolean list(PathPackResources pack, PackRoot holder, PackType type, String namespace,
                               String prefix, PackResources.ResourceOutput output) {
        if (!isJarPack(holder.nitro$root()) || prefix.isEmpty()) {
            return false;
        }
        index(pack, holder);
        if (collectResources == null || !FileUtil.decomposePath(prefix).isSuccess()) {
            return false;
        }
        List<Map.Entry<ResourceLocation, IoSupplier<InputStream>>> resources = new ArrayList<>();
        PackResources.ResourceOutput collect = (location, supplier) -> resources.add(Map.entry(location, supplier));
        try {
            Object engine = engineField.get(pack);
            if (engine == null) {
                return false;
            }
            String[] components = (String[]) decompose.invokeExact(prefix);
            collectResources.invokeExact(engine, type, namespace, components, Integer.MAX_VALUE, collect);
        } catch (Throwable exception) {
            return false;
        }
        resources.forEach(entry -> output.accept(entry.getKey(), entry.getValue()));
        return true;
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
        return query -> frozen.contains(query.relative());
    }

    @FunctionalInterface
    private interface Contains {
        boolean contains(Query query);
    }

    // FallbackResourceManager probes many packs with the same location. Elysium may also
    // probe a condition file in between, so keep the two most recent locations per thread.
    private static final ThreadLocal<Query[]> RECENT_QUERIES = ThreadLocal.withInitial(() -> new Query[2]);

    private static Query query(PackType type, ResourceLocation location) {
        Query[] recent = RECENT_QUERIES.get();
        if (recent[0] != null && recent[0].matches(type, location)) {
            return recent[0];
        }
        if (recent[1] != null && recent[1].matches(type, location)) {
            Query previous = recent[0];
            recent[0] = recent[1];
            recent[1] = previous;
        } else {
            recent[1] = recent[0];
            recent[0] = new Query(type, location);
        }
        return recent[0];
    }

    private static final class Query {
        private final PackType type;
        private final ResourceLocation location;
        private final String firstPart;
        private String relative;
        private String[] components;

        Query(PackType type, ResourceLocation location) {
            this.type = type;
            this.location = location;
            String path = location.getPath();
            int slash = path.indexOf('/');
            this.firstPart = slash < 0 ? path : path.substring(0, slash);
        }

        boolean matches(PackType type, ResourceLocation location) {
            return this.type == type && this.location == location;
        }

        String relative() {
            if (relative == null) {
                relative = type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
            }
            return relative;
        }

        String[] components() throws Throwable {
            if (components == null) {
                components = (String[]) decompose.invokeExact(relative());
            }
            return components;
        }
    }

    public static final class Index {
        static final Index UNKNOWN = new Index(null, relative -> true);
        private static final Set<String> NONE = Set.of();

        private final Path root;
        private final Contains full;
        private final ConcurrentHashMap<String, Set<String>> clientChildren = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Set<String>> serverChildren = new ConcurrentHashMap<>();

        Index(Path root, Contains full) {
            this.root = root;
            this.full = full;
        }

        /** Whether the original resource lookup can still find this location. */
        public boolean mayContain(PackType type, ResourceLocation location) {
            if (this == UNKNOWN) {
                return true;
            }
            String typeDir = type.getDirectory();
            String namespace = location.getNamespace();
            Query query = query(type, location);
            var children = type == PackType.CLIENT_RESOURCES ? clientChildren : serverChildren;
            Set<String> names = children.get(namespace);
            if (names == null) {
                names = children.computeIfAbsent(namespace, key -> listChildren(typeDir, key));
            }
            boolean possible = names == NONE_UNKNOWN || names.contains(query.firstPart);
            boolean present = possible && full.contains(query);
            if (!present) {
                MISSES_SAVED.increment();
            }
            return present;
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

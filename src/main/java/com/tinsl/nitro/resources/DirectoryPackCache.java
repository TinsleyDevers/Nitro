package com.tinsl.nitro.resources;

import com.tinsl.nitro.Nitro;
import net.minecraft.server.packs.PackResources;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.ref.Cleaner;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * In-memory copy of a resource/data pack that lives as a plain directory on disk (the
 * {@code datapacks} folder, KubeJS, Moonlight's {@code dynamic-*-pack-cache} folders).
 *
 * Every data pack reload opens each such file individually; on this pack the Sawmill mod's cached
 * recipes alone are ~29,000 files, and each open costs milliseconds when antivirus inspects it
 * (70 s of an otherwise idle reload was measured). Reading the whole tree once with all cores and
 * serving lookups and listings from memory turns that into a fraction of a second. The cache
 * belongs to one {@link PackResources} instance (vanilla directory packs and Moonlight's cached folder packs); packs are re-opened per reload, so changes on
 * disk are picked up by the next reload exactly as before.
 */
public final class DirectoryPackCache {
    private static final Map<PackResources, Cache> CACHES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final long MAX_FILE = 8L << 20;      // larger files are read from disk as before
    private static final long MAX_TOTAL = 128L << 20;
    private static final long MAX_GLOBAL = 256L << 20;
    private static final AtomicLong RETAINED = new AtomicLong();
    private static final Cleaner CLEANER = Cleaner.create();
    // Blocking file reads must not flood the common pool used by other mods.
    private static final ForkJoinPool READERS = new ForkJoinPool(Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors())));
    /** Bytes a generator just wrote to disk (Moonlight cached packs), keyed by pack root: reused instead of re-reading. */
    private static final Map<Path, Map<String, byte[]>> STAGED = new ConcurrentHashMap<>();

    /** Records the contents of a file a pack generator wrote under {@code root}, so the cache need not read it back. */
    public static void stage(Path root, String relative, byte[] bytes) {
        if (root == null || bytes == null || bytes.length > MAX_FILE) {
            return;
        }
        STAGED.computeIfAbsent(root.toAbsolutePath().normalize(), k -> new ConcurrentHashMap<>()).put(relative, bytes.clone());
    }

    public static void unstage(Path root) {
        if (root != null) {
            STAGED.remove(root.toAbsolutePath().normalize());
        }
    }

    private DirectoryPackCache() {}

    /** @return the cache for a directory pack on the default file system, or null when not applicable. */
    public static Cache get(PackResources pack, Path root) {
        if (root == null || root.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        Cache cache = CACHES.get(pack);
        if (cache == null) {
            synchronized (pack) {
                cache = CACHES.get(pack);
                if (cache == null) {
                    cache = Cache.build(pack, root);
                    CACHES.put(pack, cache);
                }
            }
        }
        return cache == Cache.DISABLED ? null : cache;
    }

    public static void invalidate(PackResources pack) {
        Cache cache = CACHES.remove(pack);
        if (cache != null && cache.cleanable != null) cache.cleanable.clean();
    }

    public static final class Cache {
        static final Cache DISABLED = new Cache(new TreeMap<>(), Map.of());
        private static final byte[] TOO_LARGE = new byte[0];

        /** relative path ("data/ns/recipes/x.json") -> contents; TOO_LARGE marks files served from disk. */
        private final NavigableMap<String, byte[]> files;
        private final Map<String, Boolean> dirs;
        private Cleaner.Cleanable cleanable;

        private Cache(NavigableMap<String, byte[]> files, Map<String, Boolean> dirs) {
            this.files = files;
            this.dirs = dirs;
        }

        /** @return contents, null if the file does not exist, or {@code TOO_LARGE} if it must be read from disk. */
        public byte[] contents(String relative) {
            return files.get(relative);
        }

        public boolean isTooLarge(byte[] contents) {
            return contents == TOO_LARGE;
        }

        public boolean hasDirectory(String relative) {
            return dirs.containsKey(relative);
        }

        /** Every file under {@code dirPrefix} ("data/ns/recipes"), in sorted order. */
        public Iterable<Map.Entry<String, byte[]>> under(String dirPrefix) {
            String from = dirPrefix + "/";
            return files.subMap(from, true, dirPrefix + "0", false).entrySet(); // '0' is the char after '/'
        }

        static Cache build(PackResources pack, Path root) {
            long start = System.nanoTime();
            if (!Files.isDirectory(root)) {
                return DISABLED;
            }
            List<Path> paths = new ArrayList<>();
            Map<String, Boolean> dirs = new ConcurrentHashMap<>();
            Path dataRoot = root.resolve("data");
            if (!Files.isDirectory(dataRoot)) return DISABLED;
            try (Stream<Path> stream = Files.walk(dataRoot)) {
                stream.forEach(p -> {
                    if (p.equals(root)) {
                        return;
                    }
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (Files.isDirectory(p)) {
                        dirs.put(rel, Boolean.TRUE);
                    } else if (Files.isRegularFile(p)) {
                        paths.add(p);
                        int slash = rel.lastIndexOf('/');
                        if (slash > 0) {
                            dirs.put(rel.substring(0, slash), Boolean.TRUE);
                        }
                    }
                });
            } catch (IOException | RuntimeException e) {
                Nitro.LOGGER.warn("Nitro: could not index directory pack {} ({}); reading it from disk", pack.packId(), e.toString());
                return DISABLED;
            }
            byte[][] contents = new byte[paths.size()][];
            AtomicLong total = new AtomicLong();
            Map<String, byte[]> staged = STAGED.remove(root.toAbsolutePath().normalize());
            try {
                READERS.submit(() -> IntStream.range(0, paths.size()).parallel().forEach(i -> {
                    Path p = paths.get(i);
                    long reserved = 0;
                    try {
                        long size = Files.size(p);
                        if (staged != null) {
                            byte[] known = staged.get(root.relativize(p).toString().replace('\\', '/'));
                            if (known != null && known.length == size && reserve(total, size, MAX_TOTAL) && reserve(RETAINED, size, MAX_GLOBAL)) {
                                contents[i] = known;
                                return;
                            }
                        }
                        if (size > MAX_FILE || !reserve(total, size, MAX_TOTAL)) {
                            contents[i] = TOO_LARGE;
                            return;
                        }
                        if (!reserve(RETAINED, size, MAX_GLOBAL)) {
                            total.addAndGet(-size);
                            contents[i] = TOO_LARGE;
                            return;
                        }
                        reserved = size;
                        // Bound the allocation even if a file grows while the reload is reading it.
                        try (var in = Files.newInputStream(p)) {
                            contents[i] = in.readNBytes((int) size);
                            if (contents[i].length != size || in.read() != -1) throw new IOException("Pack file changed while reading");
                        }
                    } catch (IOException | RuntimeException e) {
                        total.addAndGet(-reserved);
                        RETAINED.addAndGet(-reserved);
                        contents[i] = TOO_LARGE; // let the normal path report the problem
                    }
                })).join();
            } catch (RuntimeException e) {
                RETAINED.addAndGet(-total.get());
                Nitro.LOGGER.warn("Nitro: reading directory pack {} failed ({}); reading it from disk", pack.packId(), e.toString());
                return DISABLED;
            }
            TreeMap<String, byte[]> files = new TreeMap<>();
            for (int i = 0; i < paths.size(); i++) {
                files.put(root.relativize(paths.get(i)).toString().replace('\\', '/'), contents[i]);
            }
            Nitro.LOGGER.info("Nitro: loaded directory pack '{}' into memory: {} files, {} KB in {} ms",
                pack.packId(), files.size(), total.get() >> 10, (System.nanoTime() - start) / 1_000_000L);
            Cache cache = new Cache(files, dirs);
            long bytes = total.get();
            cache.cleanable = CLEANER.register(cache, () -> RETAINED.addAndGet(-bytes));
            return cache;
        }
    }

    static boolean reserve(AtomicLong counter, long bytes, long limit) {
        long current;
        do {
            current = counter.get();
            if (bytes < 0 || bytes > limit - current) return false;
        } while (!counter.compareAndSet(current, current + bytes));
        return true;
    }
}

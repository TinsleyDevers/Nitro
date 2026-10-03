package com.tinsl.nitro.resources;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import net.minecraft.server.packs.PackResources;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.ref.Cleaner;
import java.util.stream.IntStream;

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

        /**
         * Windows and macOS find "MyHouse.nbt" when asked for "myhouse.nbt", and vanilla reads packs
         * through the file system, so a pack relying on that works there. Keys are compared exactly
         * first; only a pack that has upper case names at all gets the second, case-blind lookup.
         */
        private static final boolean CASE_BLIND_FILES = caseBlindFileSystem();
        private volatile NavigableMap<String, byte[]> folded;
        private boolean hasUpperCase;

        /** @return contents, null if the file does not exist, or {@code TOO_LARGE} if it must be read from disk. */
        public byte[] contents(String relative) {
            byte[] bytes = files.get(relative);
            if (bytes != null || !CASE_BLIND_FILES || !hasUpperCase) {
                return bytes;
            }
            NavigableMap<String, byte[]> blind = folded;
            if (blind == null) {
                blind = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                blind.putAll(files);
                folded = blind;
            }
            return blind.get(relative);
        }

        private static boolean caseBlindFileSystem() {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            return os.contains("win") || os.contains("mac");
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
            long readAt = PackSnapshot.micros(FileTime.from(Instant.now()));
            if (!Files.isDirectory(root)) {
                return DISABLED;
            }
            List<Path> paths = new ArrayList<>();
            List<String> relatives = new ArrayList<>();
            List<long[]> stamps = new ArrayList<>(); // size, modification time in microseconds
            Map<String, Boolean> dirs = new ConcurrentHashMap<>();
            Path dataRoot = root.resolve("data");
            if (!Files.isDirectory(dataRoot)) return DISABLED;
            try {
                // The listing hands over size and time with each name, so nothing here opens a file.
                Files.walkFileTree(dataRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        dirs.put(relative(root, dir), Boolean.TRUE);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        BasicFileAttributes a = attrs;
                        if (a.isSymbolicLink() || a.isOther()) {
                            a = Files.readAttributes(file, BasicFileAttributes.class); // what the link points at
                        }
                        String rel = relative(root, file);
                        if (a.isDirectory()) {
                            dirs.put(rel, Boolean.TRUE);
                        } else if (a.isRegularFile()) {
                            paths.add(file);
                            relatives.add(rel);
                            stamps.add(new long[] {a.size(), PackSnapshot.micros(a.lastModifiedTime())});
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        throw exc;
                    }
                });
            } catch (IOException | RuntimeException e) {
                Nitro.LOGGER.warn("Nitro: could not index directory pack {} ({}); reading it from disk", pack.packId(), e.toString());
                return DISABLED;
            }
            int count = paths.size();
            long[] sizes = new long[count];
            long[] modified = new long[count];
            for (int i = 0; i < count; i++) {
                sizes[i] = stamps.get(i)[0];
                modified[i] = stamps.get(i)[1];
            }
            Path snapshotDir = snapshotDir();
            Path snapshotFile = snapshotDir != null && count >= PackSnapshot.MIN_FILES ? PackSnapshot.fileFor(snapshotDir, root) : null;
            Map<String, PackSnapshot.Entry> snapshot = snapshotFile == null ? Map.of() : PackSnapshot.read(snapshotFile, root);
            byte[][] contents = new byte[count][];
            AtomicLong total = new AtomicLong();
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger reused = new AtomicInteger();
            Map<String, byte[]> staged = STAGED.remove(root.toAbsolutePath().normalize());
            try {
                READERS.submit(() -> IntStream.range(0, count).parallel().forEach(i -> {
                    long size = sizes[i];
                    long reserved = 0;
                    try {
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
                        byte[] known = staged == null ? null : staged.get(relatives.get(i));
                        if (known != null && known.length == size) {
                            contents[i] = known;
                            return;
                        }
                        PackSnapshot.Entry kept = snapshot.get(relatives.get(i));
                        if (kept != null && kept.size() == size && kept.modified() == modified[i]) {
                            contents[i] = kept.bytes();
                            reused.incrementAndGet();
                            return;
                        }
                        opened.incrementAndGet();
                        // Bound the allocation even if a file grows while the reload is reading it.
                        try (var in = Files.newInputStream(paths.get(i))) {
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
            if (snapshotFile != null && (opened.get() > 0 || reused.get() != snapshot.size())) {
                PackSnapshot.writeLater(snapshotFile, root, relatives, sizes, modified, contents, TOO_LARGE, readAt);
            }
            TreeMap<String, byte[]> files = new TreeMap<>();
            for (int i = 0; i < count; i++) {
                files.put(relatives.get(i), contents[i]);
            }
            Nitro.LOGGER.info("Nitro: loaded directory pack '{}' into memory: {} files, {} KB in {} ms{}",
                pack.packId(), files.size(), total.get() >> 10, (System.nanoTime() - start) / 1_000_000L,
                snapshotFile == null ? "" : " (" + reused.get() + " from snapshot, " + opened.get() + " opened)");
            Cache cache = new Cache(files, dirs);
            for (String name : files.keySet()) {
                if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                    cache.hasUpperCase = true;
                    break;
                }
            }
            long bytes = total.get();
            cache.cleanable = CLEANER.register(cache, () -> RETAINED.addAndGet(-bytes));
            return cache;
        }
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static volatile Path snapshotDir;
    private static volatile boolean snapshotResolved;

    /** {@code .nitro/packs} in the game directory, or null when snapshots are off or there is no game directory. */
    static Path snapshotDir() {
        if (!snapshotResolved) {
            Path dir = null;
            try {
                Path gameDir = FMLPaths.GAMEDIR.get();
                if (NitroSettings.load(gameDir).directoryPackSnapshot) {
                    dir = gameDir.resolve(".nitro").resolve("packs");
                }
            } catch (Throwable ignored) {
                // no game directory (tests, tools): no snapshots
            }
            snapshotDir = dir;
            snapshotResolved = true;
        }
        return snapshotDir;
    }

    /** For tests. */
    static void useSnapshotDir(Path dir) {
        snapshotDir = dir;
        snapshotResolved = true;
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

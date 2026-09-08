package com.tinsl.nitro.launch;

import nitro.agent.boot.NitroHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * On-disk cache of transformed class bytes (see {@link NitroHooks.ClassCache}).
 *
 * <p>Correctness comes from two checks. A <em>fingerprint</em> of everything that can change what
 * the transformer pipeline produces (mod jars, config files, JVM and launch arguments, loader and
 * Nitro versions, Java version) guards the whole cache: a mismatch wipes it. A per-entry MD5 of the
 * untransformed input guards each class: bytes are only handed back for the identical input, so a
 * changed jar can never serve a stale class even inside a still-valid fingerprint. Classes whose
 * transformation refers to helper classes generated during that transformation (see
 * {@link #referencesGeneratedClasses}) are never cached.
 *
 * <p>Layout: {@code <gameDir>/.nitro/classcache/} holds {@code fingerprint}, {@code state},
 * {@code failures} and append-only segment files ({@code seg-*.bin}). Each launch reads all
 * segments (memory-mapped, so nothing is copied that is not used) and appends new entries to a
 * fresh segment on a background thread. Segments are compacted when there are many. A record is:
 * {@code [key length][key][md5 of input][output length or -1 for "unchanged"][output][crc32]}.
 * A truncated tail (killed JVM) or a crc mismatch just drops that record.
 *
 * <p>Crash-loop guard: {@code state} is set to {@code booting} at start and to {@code ok} once the
 * game reports a working state. Two consecutive launches that never got there wipe the cache and
 * skip it for the next launch, in case a cached class was involved.
 */
final class ClassTransformCache implements NitroHooks.ClassCache {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final String REASON = "classloading";
    private static final int MAGIC = 0x4E495443; // "NITC"
    private static final int VERSION = 1;
    private static final long MAX_TOTAL_BYTES = 768L << 20;
    private static final int COMPACT_AT = 6;

    private record Entry(byte[] inputHash, ByteBuffer segment, int offset, int length) {
        boolean identity() {
            return length < 0;
        }
    }

    private final Path dir;
    private Path gameDir;
    private final ConcurrentHashMap<String, Entry> index = new ConcurrentHashMap<>(1 << 17);
    private final Map<Object, Integer> transformerIds = new IdentityHashMap<>();
    private final ThreadLocal<MessageDigest> md5 = ThreadLocal.withInitial(ClassTransformCache::newMd5);
    private final LinkedBlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong identityHits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong uncacheable = new AtomicLong();
    private final AtomicLong bytesOnDisk = new AtomicLong();
    private volatile boolean writing = true;
    private volatile boolean clean;
    private Thread writer;

    private ClassTransformCache(Path dir) {
        this.dir = dir;
    }

    /** @return an installed cache, or null when the cache is disabled or unusable. */
    static ClassTransformCache install(Path gameDir) {
        Path dir = gameDir.resolve(".nitro").resolve("classcache");
        try {
            Files.createDirectories(dir);
            ClassTransformCache cache = new ClassTransformCache(dir);
            if (!cache.open(gameDir)) {
                return null;
            }
            return cache;
        } catch (Throwable t) {
            LOGGER.warn("Nitro: class cache disabled ({})", t.toString());
            return null;
        }
    }

    private boolean open(Path gameDir) throws IOException {
        long start = System.nanoTime();
        Path fingerprintFile = dir.resolve("fingerprint");
        Path stateFile = dir.resolve("state");
        Path failuresFile = dir.resolve("failures");

        Path detailFile = dir.resolve("fingerprint-detail.txt");
        String detail = fingerprintDetail(gameDir);
        String fingerprint = hashOf(detail);
        String storedFingerprint = Files.isRegularFile(fingerprintFile) ? Files.readString(fingerprintFile).trim() : "";
        String state = Files.isRegularFile(stateFile) ? Files.readString(stateFile).trim() : "ok";
        int failures = 0;
        if (Files.isRegularFile(failuresFile)) {
            try {
                failures = Integer.parseInt(Files.readString(failuresFile).trim());
            } catch (NumberFormatException ignored) {
                // treated as zero
            }
        }

        boolean wipe = false;
        boolean skip = false;
        if (!storedFingerprint.equals(fingerprint)) {
            if (!storedFingerprint.isEmpty()) {
                LOGGER.info("Nitro: mods, configs or launch arguments changed since the class cache was written; starting a fresh cache");
                reportDifferences(detailFile, detail);
            }
            wipe = true;
            failures = 0;
        } else if ("booting".equals(state)) {
            failures++;
            if (failures >= 2) {
                LOGGER.warn("Nitro: the last {} launches did not reach a working state; discarding the class cache and running "
                    + "this launch without it (it is rebuilt on the next launch)", failures);
                wipe = true;
                skip = true;
                failures = 0;
            } else {
                LOGGER.info("Nitro: the previous launch did not reach a working state (closed early?); class cache kept, "
                    + "one more such launch discards it");
            }
        } else {
            failures = 0;
        }

        if (wipe) {
            wipeSegments();
        }
        Files.writeString(fingerprintFile, fingerprint);
        Files.writeString(detailFile, detail);
        Files.writeString(failuresFile, Integer.toString(failures));
        this.gameDir = gameDir;
        if (skip) {
            Files.writeString(stateFile, "ok");
            return false;
        }
        Files.writeString(stateFile, "booting");

        List<Path> segments = segments();
        if (segments.size() >= COMPACT_AT) {
            segments = compact(segments);
        }
        for (Path segment : segments) {
            load(segment);
        }
        Path fresh = dir.resolve("seg-" + Long.toHexString(System.currentTimeMillis()) + ".bin");
        writer = new Thread(() -> writeLoop(fresh), "Nitro class cache writer");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "Nitro class cache shutdown"));
        LOGGER.info("Nitro: class cache ready: {} cached classes in {} segment(s), {} MB, opened in {} ms",
            index.size(), segments.size(), bytesOnDisk.get() >> 20, (System.nanoTime() - start) / 1_000_000L);
        return true;
    }

    // --- lookup / store ------------------------------------------------------------------------------

    @Override
    public byte[] lookup(Object transformer, byte[] input, String className, String reason) {
        if (!REASON.equals(reason) || input.length == 0) {
            return null;
        }
        Entry e = index.get(key(transformer, className));
        if (e == null) {
            misses.incrementAndGet();
            return null;
        }
        byte[] hash = md5.get().digest(input);
        if (!Arrays.equals(hash, e.inputHash)) {
            misses.incrementAndGet();
            return null;
        }
        if (e.identity()) {
            identityHits.incrementAndGet();
            return input;
        }
        byte[] out = new byte[e.length];
        e.segment.get(e.offset, out);
        hits.incrementAndGet();
        return out;
    }

    @Override
    public void store(Object transformer, byte[] input, String className, String reason, byte[] result) {
        if (!writing || !REASON.equals(reason) || input.length == 0 || result.length == 0) {
            return;
        }
        String key = key(transformer, className);
        byte[] hash = md5.get().digest(input);
        Entry existing = index.get(key);
        if (existing != null && Arrays.equals(hash, existing.inputHash)) {
            return;
        }
        boolean identity = result == input || Arrays.equals(result, input);
        if (!identity && referencesGeneratedClasses(input, result)) {
            uncacheable.incrementAndGet();
            return;
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int payload = identity ? 0 : result.length;
        ByteBuffer record = ByteBuffer.allocate(4 + keyBytes.length + 16 + 4 + payload + 4);
        record.putInt(keyBytes.length).put(keyBytes).put(hash).putInt(identity ? -1 : result.length);
        if (!identity) {
            record.put(result);
        }
        CRC32 crc = new CRC32();
        crc.update(record.array(), 0, record.position());
        record.putInt((int) crc.getValue());
        // Later lookups in this launch are served from memory too (a class can be asked for twice).
        ByteBuffer copy = identity ? null : ByteBuffer.wrap(Arrays.copyOf(result, result.length));
        index.put(key, new Entry(hash, copy, 0, identity ? -1 : result.length));
        writeQueue.add(record.array());
        stored.incrementAndGet();
    }

    @Override
    public void markClean() {
        if (clean) {
            return;
        }
        clean = true;
        try {
            Files.writeString(dir.resolve("state"), "ok");
            Files.writeString(dir.resolve("failures"), "0");
        } catch (IOException e) {
            LOGGER.warn("Nitro: could not record class cache state: {}", e.toString());
        }
        // Mods rewrite their config files while loading (791 files on the reference pack, some with
        // changed content such as timestamps). The state that matters for the next launch is the
        // state those files are in now, after loading, so the fingerprint is refreshed here (and
        // again at exit, for mods that save on shutdown).
        // Mods also create config files while playing (per-biome files, generated defaults), and a
        // crash or a kill skips the exit refresh, so the fingerprint is refreshed every minute
        // as well; each refresh is ~0.5 s of parallel hashing.
        Thread refresh = new Thread(() -> {
            refreshFingerprint();
            while (true) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    return;
                }
                refreshFingerprint();
            }
        }, "Nitro class cache fingerprint");
        refresh.setDaemon(true);
        refresh.start();
        LOGGER.info("Nitro: class cache this launch: {} classes served from cache ({} transformed, {} unchanged), "
            + "{} not cached, {} newly stored, {} not cacheable (refer to classes generated while transforming)",
            hits.get() + identityHits.get(), hits.get(), identityHits.get(), misses.get(), stored.get(), uncacheable.get());
    }

    /**
     * Some of what Mixin produces is only valid within the launch that produced it, and is referred
     * to from other classes:
     * <ul>
     *   <li>helper classes generated while a mixin is applied: anonymous inner classes of mixins
     *       (renamed with a random id), {@code Args$N} argument bundles (numbered in order of first
     *       use), MixinExtras local-ref implementations (defined directly in its class loader);</li>
     *   <li>bridge methods for {@code @Accessor}/{@code @Invoker}, named with Mixin's per-launch
     *       session id and a per-target counter, called from the accessor interface.</li>
     * </ul>
     * A class whose transformed bytes contain any of these is transformed on every launch instead
     * of being cached, which keeps every such pair consistent. Real classes referenced by mixin
     * code are jar resources and are fine.
     */
    private static boolean referencesGeneratedClasses(byte[] input, byte[] result) {
        java.util.Set<String> before = new java.util.HashSet<>();
        if (!scanConstantPool(input, before, null)) {
            return true;
        }
        java.util.Set<String> after = new java.util.HashSet<>();
        java.util.Set<String> sessionNames = new java.util.HashSet<>();
        if (!scanConstantPool(result, after, sessionNames)) {
            return true;
        }
        if (!sessionNames.isEmpty()) {
            return true;
        }
        for (String ref : after) {
            if (!before.contains(ref) && GENERATED_NAME.matcher(ref).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern GENERATED_NAME = java.util.regex.Pattern.compile(
        "\\$[0-9a-f]{32}(\\$|$)|/synthetic/args/Args\\$|mixinextras/.*generated|\\$Anonymous\\$");
    private static final java.util.regex.Pattern SESSION_NAME = java.util.regex.Pattern.compile("\\$md\\$[0-9a-f]{6}\\$");

    /**
     * Collects CONSTANT_Class names into {@code classes} and, when {@code sessionNames} is given,
     * every CONSTANT_Utf8 that carries Mixin's session id. @return false if the bytes do not parse.
     */
    private static boolean scanConstantPool(byte[] bytes, java.util.Set<String> classes, java.util.Set<String> sessionNames) {
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            char[] buf = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset <= 0) {
                    continue;
                }
                int tag = bytes[offset - 1];
                if (tag == 7) { // CONSTANT_Class: u2 name index
                    classes.add(reader.readUTF8(offset, buf));
                } else if (tag == 1 && sessionNames != null) { // CONSTANT_Utf8: u2 length, bytes
                    int length = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
                    if (length >= 12) {
                        String s = new String(bytes, offset + 2, length, StandardCharsets.ISO_8859_1);
                        if (s.indexOf("$md$") >= 0 && SESSION_NAME.matcher(s).find()) {
                            sessionNames.add(s);
                        }
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String key(Object transformer, String className) {
        int id;
        synchronized (transformerIds) {
            id = transformerIds.computeIfAbsent(transformer, t -> transformerIds.size());
        }
        return id + ":" + className;
    }

    // --- segments -----------------------------------------------------------------------------------

    private List<Path> segments() throws IOException {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("seg-") && p.getFileName().toString().endsWith(".bin"))
                .sorted()
                .forEach(out::add);
        }
        return out;
    }

    private void wipeSegments() throws IOException {
        for (Path p : segments()) {
            Files.deleteIfExists(p);
        }
        index.clear();
    }

    private void load(Path segment) {
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size < 8 || size > Integer.MAX_VALUE) {
                return;
            }
            ByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            if (buf.getInt(0) != MAGIC || buf.getInt(4) != VERSION) {
                LOGGER.warn("Nitro: class cache segment {} has an unknown format; ignored", segment.getFileName());
                return;
            }
            int pos = 8;
            int records = 0;
            CRC32 crc = new CRC32();
            while (pos + 4 <= size) {
                int start = pos;
                int keyLen = buf.getInt(pos);
                pos += 4;
                if (keyLen <= 0 || keyLen > 4096 || pos + keyLen + 16 + 4 > size) {
                    break;
                }
                byte[] keyBytes = new byte[keyLen];
                buf.get(pos, keyBytes);
                pos += keyLen;
                byte[] hash = new byte[16];
                buf.get(pos, hash);
                pos += 16;
                int outLen = buf.getInt(pos);
                pos += 4;
                int payload = Math.max(outLen, 0);
                if (outLen < -1 || pos + (long) payload + 4 > size) {
                    break;
                }
                int dataOffset = pos;
                pos += payload;
                int storedCrc = buf.getInt(pos);
                pos += 4;
                crc.reset();
                crc.update(buf.slice(start, pos - 4 - start));
                if ((int) crc.getValue() != storedCrc) {
                    continue;
                }
                index.put(new String(keyBytes, StandardCharsets.UTF_8), new Entry(hash, buf, dataOffset, outLen));
                records++;
            }
            bytesOnDisk.addAndGet(size);
            LOGGER.debug("Nitro: class cache segment {}: {} records", segment.getFileName(), records);
        } catch (IOException e) {
            LOGGER.warn("Nitro: could not read class cache segment {}: {}", segment.getFileName(), e.toString());
        }
    }

    /** Rewrites all segments into one (only the live entries); runs before this launch maps anything. */
    private List<Path> compact(List<Path> segments) throws IOException {
        for (Path p : segments) {
            load(p);
        }
        Path merged = dir.resolve("seg-" + Long.toHexString(System.currentTimeMillis() - 1) + ".tmp");
        try (OutputStream raw = Files.newOutputStream(merged);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            CRC32 crc = new CRC32();
            for (Map.Entry<String, Entry> e : new TreeMap<>(index).entrySet()) {
                byte[] keyBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                Entry v = e.getValue();
                int payload = v.identity() ? 0 : v.length;
                ByteBuffer record = ByteBuffer.allocate(4 + keyBytes.length + 16 + 4 + payload);
                record.putInt(keyBytes.length).put(keyBytes).put(v.inputHash).putInt(v.length);
                if (!v.identity()) {
                    byte[] data = new byte[v.length];
                    v.segment.get(v.offset, data);
                    record.put(data);
                }
                crc.reset();
                crc.update(record.array(), 0, record.position());
                out.write(record.array(), 0, record.position());
                out.writeInt((int) crc.getValue());
            }
        }
        index.clear();
        bytesOnDisk.set(0);
        // The old segments' mapped buffers are unreachable now; on Windows a mapped file cannot be
        // deleted until its buffer is collected, so give the collector a chance first.
        System.gc();
        List<Path> undeleted = new ArrayList<>();
        for (Path p : segments) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                undeleted.add(p);
            }
        }
        Path target = merged.resolveSibling(merged.getFileName().toString().replace(".tmp", ".bin"));
        if (!undeleted.isEmpty()) {
            LOGGER.info("Nitro: {} old class cache segment(s) still in use; compaction retried next launch", undeleted.size());
            Files.deleteIfExists(merged);
            return segments;
        }
        Files.move(merged, target);
        LOGGER.info("Nitro: compacted {} class cache segments into one", segments.size());
        return List.of(target);
    }

    private void writeLoop(Path segment) {
        try (OutputStream raw = Files.newOutputStream(segment, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            long written = 8;
            while (true) {
                byte[] record = writeQueue.poll(1, TimeUnit.SECONDS);
                if (record == null) {
                    if (!writing && writeQueue.isEmpty()) {
                        break;
                    }
                    out.flush();
                    continue;
                }
                if (record.length == 0) {
                    break; // shutdown sentinel
                }
                out.write(record);
                written += record.length;
                if (bytesOnDisk.get() + written > MAX_TOTAL_BYTES) {
                    LOGGER.info("Nitro: class cache reached its size limit; no more entries stored this launch");
                    writing = false;
                    writeQueue.clear();
                    break;
                }
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            LOGGER.warn("Nitro: class cache writer stopped: {}", e.toString());
            writing = false;
        }
    }

    private void shutdown() {
        writing = false;
        writeQueue.add(new byte[0]);
        try {
            writer.join(5000);
        } catch (InterruptedException ignored) {
            // shutting down anyway
        }
        if (clean) {
            refreshFingerprint();
        }
        if (!clean) {
            LOGGER.info("Nitro: class cache: {} served, {} stored this launch (game closed before load completed)",
                hits.get() + identityHits.get(), stored.get());
        }
    }

    // --- fingerprint --------------------------------------------------------------------------------

    private synchronized void refreshFingerprint() {
        try {
            String detail = fingerprintDetail(gameDir);
            Files.writeString(dir.resolve("fingerprint-detail.txt"), detail);
            Files.writeString(dir.resolve("fingerprint"), hashOf(detail));
        } catch (IOException e) {
            LOGGER.debug("Nitro: could not refresh the class cache fingerprint: {}", e.toString());
        }
    }

    private static void reportDifferences(Path detailFile, String current) {
        if (!Files.isRegularFile(detailFile)) {
            return;
        }
        try {
            java.util.Set<String> before = new java.util.HashSet<>(Files.readAllLines(detailFile));
            java.util.Set<String> now = new java.util.HashSet<>(Arrays.asList(current.split("\n")));
            java.util.Set<String> beforePaths = new java.util.HashSet<>();
            java.util.Set<String> nowPaths = new java.util.HashSet<>();
            for (String line : before) {
                beforePaths.add(pathOf(line));
            }
            for (String line : now) {
                nowPaths.add(pathOf(line));
            }
            int shown = 0;
            int total = 0;
            for (String line : now) {
                if (!before.contains(line)) {
                    total++;
                    if (shown < 8) {
                        LOGGER.info("Nitro:   {}: {}", beforePaths.contains(pathOf(line)) ? "changed" : "new", shorten(line));
                        shown++;
                    }
                }
            }
            for (String line : before) {
                if (!nowPaths.contains(pathOf(line))) {
                    total++;
                    if (shown < 8) {
                        LOGGER.info("Nitro:   removed: {}", shorten(line));
                        shown++;
                    }
                }
            }
            if (total > shown) {
                LOGGER.info("Nitro:   ... and {} more", total - shown);
            }
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private static String shorten(String line) {
        return line.length() > 160 ? line.substring(0, 160) : line;
    }

    private static String pathOf(String line) {
        int sp = line.indexOf(' ');
        return sp < 0 ? line : line.substring(0, sp);
    }

    private static String hashOf(String detail) {
        return HexFormat.of().formatHex(newMd5().digest(detail.getBytes(StandardCharsets.UTF_8)));
    }

    private static String fingerprintDetail(Path gameDir) throws IOException {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("nitro-classcache-v").append(VERSION).append('\n');
        sb.append("java=").append(Runtime.version()).append('\n');
        sb.append("nitro=").append(Objects.requireNonNullElse(ClassTransformCache.class.getPackage().getImplementationVersion(), "dev")).append('\n');
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (isRelevantJvmArg(arg)) {
                sb.append("jvm=").append(arg).append('\n');
            }
        }
        String[] cmd = System.getProperty("sun.java.command", "").split(" ");
        for (int i = 0; i < cmd.length; i++) {
            String a = cmd[i];
            if (a.startsWith("--fml.") || a.equals("--launchTarget") || a.equals("--version")) {
                sb.append("arg=").append(a);
                if (i + 1 < cmd.length && !cmd[i + 1].startsWith("--")) {
                    sb.append(' ').append(cmd[++i]);
                }
                sb.append('\n');
            }
        }
        // The Nitro jar sits in mods/ with the others, so mod, loader-service and Nitro updates all
        // change this list. FML's own jars are named by version in the launch arguments above.
        listTree(gameDir.resolve("mods"), sb, "mod", false);
        listTree(gameDir.resolve("config"), sb, "cfg", true);
        listTree(gameDir.resolve("defaultconfigs"), sb, "dcfg", true);
        return sb.toString();
    }

    private static boolean isRelevantJvmArg(String arg) {
        return arg.startsWith("-Dmixin") || arg.startsWith("-Dfml") || arg.startsWith("-Dneoforge") || arg.startsWith("-Dforge")
            || arg.startsWith("-Dnitro") || arg.startsWith("-Dconnector") || arg.startsWith("-Dmodernfix") || arg.startsWith("-Dembeddium")
            || arg.startsWith("-Dsodium") || arg.startsWith("-Dbsl") || arg.startsWith("-Dlegacy") || arg.startsWith("-javaagent")
            || arg.startsWith("-Dproduction") || arg.startsWith("-Dmodloader");
    }

    /**
     * Appends one line per file: path, size and either a content hash (configs, which mods rewrite
     * with identical content on every launch) or the modification time (jars).
     */
    private static void listTree(Path root, StringBuilder sb, String tag, boolean hashContent) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).filter(f -> !hashContent || isConfigFile(f)).forEach(files::add);
        }
        files.sort(null);
        // One line per file; hashing runs in parallel because each open costs milliseconds when
        // antivirus inspects it.
        String[] lines = new String[files.size()];
        java.util.stream.IntStream.range(0, files.size()).parallel().forEach(i -> {
            Path f = files.get(i);
            String rel = root.relativize(f).toString().replace('\\', '/');
            StringBuilder line = new StringBuilder(160);
            try {
                BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
                line.append(tag).append('=').append(rel).append(' ').append(attrs.size());
                if (hashContent && attrs.size() <= (2L << 20)) {
                    line.append(' ').append(HexFormat.of().formatHex(newMd5().digest(Files.readAllBytes(f))));
                } else {
                    line.append(' ').append(attrs.lastModifiedTime().toMillis());
                }
            } catch (IOException e) {
                line.setLength(0);
                line.append(tag).append('=').append(rel).append(" unreadable");
            }
            lines[i] = line.toString();
        });
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }

    /**
     * Mods keep more than configuration under config/: Lucene search indexes (Ars Nouveau), error
     * logs (Bountiful), caches. Only files in configuration formats take part in the fingerprint.
     */
    private static boolean isConfigFile(Path f) {
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "toml", "json", "json5", "jsonc", "cfg", "properties", "yml", "yaml", "snbt", "conf", "hocon", "ini", "txt", "xml", "nbt", "dat" -> true;
            default -> false;
        };
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

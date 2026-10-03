package com.tinsl.nitro.resources;

import com.tinsl.nitro.Nitro;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One compressed file per large folder pack in {@code .nitro/packs}, holding every file of the pack
 * together with the size and modification time it had when it was read.
 *
 * Reading a folder pack opens every file in it, and on Windows each open is inspected by the
 * antivirus: Sawmill's ~29,000 cached recipes took 11 to 13 s per world join even with all cores
 * reading. Listing the folder with sizes and times does not open anything (60 ms for the same
 * folder), so a file whose size and time match the snapshot is taken from it and only changed files
 * are opened. That is the same test {@code make} and {@code rsync} use to decide a file is unchanged.
 *
 * A file whose time is within a few seconds of the moment it was read is left out of the snapshot,
 * so a write landing in the same timestamp tick as the read (coarse clocks on some file systems)
 * cannot leave stale bytes behind; it is simply read from disk again next time.
 */
final class PackSnapshot {
    /** Folder packs with fewer files are cheap to read and not worth a snapshot. */
    static final int MIN_FILES = 256;
    private static final int MAGIC = 0x4E545053; // "NTPS"
    private static final int VERSION = 1;
    private static final long RACY_MICROS = 3_000_000L;
    private static final Duration UNUSED_FOR = Duration.ofDays(30);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Nitro pack snapshot");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    record Entry(long size, long modified, byte[] bytes) {}

    private PackSnapshot() {}

    /** The snapshot file for a pack root: readable name plus a hash of the full path. */
    static Path fileFor(Path dir, Path root) {
        Path abs = root.toAbsolutePath().normalize();
        String name = abs.getFileName() == null ? "root" : abs.getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 48) {
            name = name.substring(0, 48);
        }
        CRC32 crc = new CRC32();
        crc.update(abs.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return dir.resolve(name + "-" + Long.toHexString(crc.getValue()) + ".bin");
    }

    /** @return relative path to entry, or an empty map when there is no usable snapshot. */
    static Map<String, Entry> read(Path file, Path root) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
            new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION
                || !in.readUTF().equals(root.toAbsolutePath().normalize().toString())) {
                return Map.of();
            }
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("negative entry count");
            }
            Map<String, Entry> entries = HashMap.newHashMap(count);
            for (int i = 0; i < count; i++) {
                String relative = in.readUTF();
                long size = in.readLong();
                long modified = in.readLong();
                if (size < 0 || size > Integer.MAX_VALUE) {
                    throw new IOException("bad entry size " + size);
                }
                byte[] bytes = new byte[(int) size];
                in.readFully(bytes);
                entries.put(relative, new Entry(size, modified, bytes));
            }
            if (in.read() != -1) { // reading to the end also checks the gzip checksum
                throw new IOException("trailing data");
            }
            touch(file);
            return entries;
        } catch (IOException | RuntimeException e) {
            Nitro.LOGGER.info("Nitro: ignoring unreadable pack snapshot {} ({}); it will be rebuilt", file.getFileName(), e.toString());
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // rewritten below anyway
            }
            return Map.of();
        }
    }

    /**
     * Writes the snapshot in the background, lowest priority, so the reload never waits for it.
     * Entries whose time is too close to {@code readAtMicros} are left out (see the class comment).
     */
    static void writeLater(Path file, Path root, List<String> relatives, long[] sizes, long[] modified, byte[][] contents,
                           byte[] tooLarge, long readAtMicros) {
        WRITER.execute(() -> {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.getParent());
                int count = 0;
                for (int i = 0; i < contents.length; i++) {
                    if (keep(contents[i], tooLarge, modified[i], readAtMicros)) {
                        count++;
                    }
                }
                try (OutputStream raw = Files.newOutputStream(tmp);
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FastGzip(raw), 1 << 16))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeUTF(root.toAbsolutePath().normalize().toString());
                    out.writeInt(count);
                    for (int i = 0; i < contents.length; i++) {
                        if (!keep(contents[i], tooLarge, modified[i], readAtMicros)) {
                            continue;
                        }
                        out.writeUTF(relatives.get(i));
                        out.writeLong(sizes[i]);
                        out.writeLong(modified[i]);
                        out.write(contents[i]);
                    }
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                pruneUnused(file.getParent());
            } catch (IOException | RuntimeException e) {
                Nitro.LOGGER.debug("Nitro: could not write pack snapshot {}: {}", file.getFileName(), e.toString());
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // left for the next write to replace
                }
            }
        });
    }

    private static boolean keep(byte[] contents, byte[] tooLarge, long modified, long readAtMicros) {
        return contents != null && contents != tooLarge && modified < readAtMicros - RACY_MICROS;
    }

    /** Snapshots of packs that have not been loaded for a month (deleted worlds, removed mods) go. */
    private static void pruneUnused(Path dir) {
        Instant cutoff = Instant.now().minus(UNUSED_FOR);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".bin")).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // in use by another instance; next time
                }
            });
        } catch (IOException ignored) {
            // nothing to prune
        }
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // only used for pruning
        }
    }

    /** JSON compresses well even at the fastest level, and the write is off the loading path anyway. */
    private static final class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream out) throws IOException {
            super(out, 1 << 16);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    /** For tests: waits until queued snapshot writes are done. */
    static void awaitWrites() throws Exception {
        WRITER.submit(() -> {}).get();
    }

    static long micros(java.nio.file.attribute.FileTime time) {
        return time.to(java.util.concurrent.TimeUnit.MICROSECONDS);
    }
}

package com.tinsl.nitro.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Extracts jars embedded in the Nitro launcher jar into {@code <gameDir>/.nitro}, keyed by content hash. */
final class NitroLaunchFiles {
    private NitroLaunchFiles() {}

    static Path extract(Path gameDir, String resource, String baseName) throws IOException {
        byte[] bytes;
        try (InputStream in = NitroLaunchFiles.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("embedded resource " + resource + " missing from Nitro jar");
            }
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path dir = gameDir.resolve(".nitro");
        Files.createDirectories(dir);
        Path target = dir.resolve(baseName + "-" + hash + ".jar");
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
            Path tmp = dir.resolve(baseName + "-" + hash + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // Remove stale copies from previous versions.
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(baseName + "-") && n.endsWith(".jar") && !p.equals(target);
                })
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // in use by another instance; harmless
                    }
                });
        }
        return target;
    }
}

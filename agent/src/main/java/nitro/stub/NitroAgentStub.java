package nitro.stub;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The file the player points {@code -javaagent} at: {@code <game dir>/.nitro/nitro-agent.jar}.
 *
 * Nitro only gets JVM agent access when the player adds it to the JVM arguments themselves; it never
 * attaches to the game on its own. That makes the path in the argument a thing the player types once,
 * so it has to stay valid across Nitro updates, and a JVM refuses to start at all when a
 * {@code -javaagent} file is missing. Hence this stub: it never changes between Nitro versions and
 * holds no patching code. It finds the Nitro jar that is actually in the mods folder, takes the
 * agent that jar carries and hands over to it. Updating Nitro updates the agent; the argument stays.
 *
 * Nothing in here may throw: an exception out of premain stops the JVM. If anything goes wrong,
 * or Nitro is not installed in this game directory (a launcher-wide argument used by another
 * instance, say), the game starts normally without Nitro's launch patches.
 *
 * Uses java.base only. The class sits on the system class path, which is why it has its own
 * package, separate from the agent it loads onto the bootstrap class path.
 */
public final class NitroAgentStub {
    private static final String EMBEDDED_AGENT = "META-INF/nitro/nitro-agent.jar";

    private NitroAgentStub() {}

    public static void premain(String args, Instrumentation inst) {
        long start = System.nanoTime();
        try {
            Path gameDir = gameDir(args);
            if (gameDir == null) {
                note("could not tell which game directory this is; launch patches skipped");
                return;
            }
            Path nitroJar = findNitro(gameDir.resolve("mods"));
            if (nitroJar == null) {
                // Nitro is not installed here. Not an error: the argument may be shared between instances.
                System.setProperty("nitro.agentStub", "no-nitro-jar");
                return;
            }
            Path core = extractAgent(gameDir, nitroJar);
            inst.appendToBootstrapClassLoaderSearch(new JarFile(core.toFile()));
            Class<?> agent = Class.forName("nitro.agent.NitroAgent", true, null);
            agent.getMethod("install", Instrumentation.class, String.class).invoke(null, inst, gameDir.toString());
            System.setProperty("nitro.agentStartMillis", Long.toString((System.nanoTime() - start) / 1_000_000L));
        } catch (Throwable t) {
            note("failed, the game continues without launch patches: " + t);
        }
    }

    private static void note(String message) {
        System.setProperty("nitro.resolverPatchError", "agent: " + message);
        System.err.println("[Nitro] agent " + message);
    }

    /**
     * The game directory, from (in order) an explicit {@code -javaagent:...=<dir>} argument, the
     * {@code --gameDir} launch argument, or the working directory. Launchers set the working directory
     * to the game directory; the command line check covers the ones that pass it explicitly. A
     * candidate only counts when it has a mods folder.
     */
    static Path gameDir(String args) {
        List<String> candidates = new ArrayList<>();
        if (args != null && !args.isBlank()) {
            candidates.add(args.trim());
        }
        String fromCommand = gameDirFromCommand(System.getProperty("sun.java.command", ""));
        if (fromCommand != null) {
            candidates.add(fromCommand);
        }
        candidates.add(System.getProperty("user.dir", "."));
        for (String c : candidates) {
            try {
                Path p = Paths.get(c).toAbsolutePath().normalize();
                if (Files.isDirectory(p.resolve("mods"))) {
                    return p;
                }
            } catch (RuntimeException ignored) {
                // not a usable path, try the next one
            }
        }
        return null;
    }

    /**
     * Pulls the {@code --gameDir} value out of the command line. The JVM joins arguments with
     * spaces, so a path with spaces in it is read up to the next {@code " --"} argument.
     */
    static String gameDirFromCommand(String command) {
        int i = command.indexOf("--gameDir ");
        if (i < 0) {
            return null;
        }
        String rest = command.substring(i + "--gameDir ".length());
        int end = rest.indexOf(" --");
        String value = (end < 0 ? rest : rest.substring(0, end)).trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * The jar in the mods folder that carries Nitro's agent. Jars with "nitro" in the name are checked
     * first, which is one directory listing and one zip opened; every jar is checked only when the file
     * was renamed to something else.
     */
    static Path findNitro(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) {
            return null;
        }
        List<Path> named = new ArrayList<>();
        List<Path> others = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : jars) {
                (jar.getFileName().toString().toLowerCase(Locale.ROOT).contains("nitro") ? named : others).add(jar);
            }
        }
        for (List<Path> group : List.of(named, others)) {
            for (Path jar : group) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    if (zip.getEntry(EMBEDDED_AGENT) != null) {
                        return jar;
                    }
                } catch (IOException ignored) {
                    // not a readable jar; keep looking
                }
            }
        }
        return null;
    }

    /**
     * Copies the agent out of the Nitro jar to {@code .nitro/agent/agent-<crc>.jar}, reusing the copy
     * when it is already there, and removes copies from earlier versions that are not in use.
     */
    static Path extractAgent(Path gameDir, Path nitroJar) throws IOException {
        byte[] bytes;
        try (ZipFile zip = new ZipFile(nitroJar.toFile())) {
            ZipEntry entry = zip.getEntry(EMBEDDED_AGENT);
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        Path dir = gameDir.resolve(".nitro").resolve("agent");
        Files.createDirectories(dir);
        String name = String.format("agent-%08x.jar", crc.getValue());
        Path target = dir.resolve(name);
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
            Path tmp = dir.resolve(name + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "agent-*.jar")) {
            for (Path p : old) {
                if (!p.equals(target)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // held open by another running instance
                    }
                }
            }
        }
        return target;
    }
}

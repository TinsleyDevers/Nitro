package com.tinsl.nitro.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds Java installations already on this computer, so the advice to switch to Java 25 can say
 * "this one" instead of sending the player off to download and then locate one.
 *
 * Every Java build has a {@code release} file in its home folder with a {@code JAVA_VERSION} line,
 * so this only lists a handful of known folders (launcher runtimes, the usual vendor install
 * folders, IDE and Gradle downloads) a few levels deep and reads that one line. Nothing is run.
 * Most players with Prism already have a Java 25 from it, because Prism downloads one for newer
 * Minecraft versions.
 *
 * Dependency free, like {@link NitroSettings}.
 */
public final class JavaFinder {
    /** The version the advice is about: the one the module resolver fix was measured on. */
    public static final int WANTED = 25;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_DIRECTORIES = 600;

    /**
     * @param home       the Java home folder
     * @param executable the program a launcher should be pointed at (javaw.exe on Windows)
     * @param version    the full version from the release file
     */
    public record Install(Path home, Path executable, String version) {
        public int feature() {
            return feature(version);
        }

        static int feature(String version) {
            String v = version.startsWith("1.") ? version.substring(2) : version;
            int end = 0;
            while (end < v.length() && Character.isDigit(v.charAt(end))) {
                end++;
            }
            return end == 0 ? -1 : Integer.parseInt(v.substring(0, end));
        }
    }

    private JavaFinder() {}

    /**
     * The Java {@link #WANTED} installs on this machine, the ones belonging to the launcher that started
     * the game first (a launcher's own runtime is the one its Java settings list by default).
     */
    public static List<Install> find(Path gameDir) {
        List<Install> found = new ArrayList<>();
        for (Path root : roots(gameDir)) {
            scan(root, found);
        }
        found.removeIf(i -> i.feature() != WANTED);
        return found;
    }

    /** Folders that commonly hold Java installs, most specific first. */
    static List<Path> roots(Path gameDir) {
        Set<Path> roots = new LinkedHashSet<>();
        // The launcher's own data folder sits a few levels above the game folder
        // (PrismLauncher/instances/<name>/minecraft, ModrinthApp/profiles/<name>, ...).
        Path p = gameDir == null ? null : gameDir.toAbsolutePath().normalize();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            for (String sub : List.of("java", "runtime", "runtimes", "jre", "meta/java_versions", "Install/runtime")) {
                roots.add(p.resolve(sub));
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            Path h = Path.of(home);
            for (String sub : List.of(".jdks", ".gradle/jdks", ".sdkman/candidates/java", "Library/Java/JavaVirtualMachines",
                "AppData/Roaming/PrismLauncher/java", "AppData/Roaming/.minecraft/runtime", "AppData/Roaming/ModrinthApp/meta/java_versions",
                "AppData/Roaming/ATLauncher/runtimes", "curseforge/minecraft/Install/runtime", ".local/share/PrismLauncher/java",
                "Library/Application Support/PrismLauncher/java", "Library/Application Support/minecraft/runtime")) {
                roots.add(h.resolve(sub));
            }
        }
        for (String env : List.of("ProgramFiles", "ProgramW6432", "LOCALAPPDATA")) {
            String dir = System.getenv(env);
            if (dir == null) {
                continue;
            }
            Path base = Path.of(dir);
            for (String vendor : List.of("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft",
                "Semeru", "Eclipse Foundation", "Programs/Eclipse Adoptium")) {
                roots.add(base.resolve(vendor));
            }
        }
        roots.add(Path.of("/usr/lib/jvm"));
        roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
        return new ArrayList<>(roots);
    }

    /** Breadth first, a few levels deep, never descending into a Java home once one is found. */
    static void scan(Path root, List<Install> out) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Deque<Path> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_DIRECTORIES) {
            Path dir = queue.poll();
            int depth = depths.poll();
            Install install = read(dir);
            if (install != null) {
                if (out.stream().noneMatch(i -> sameFile(i.home(), install.home()))) {
                    out.add(install);
                }
                continue;
            }
            if (depth >= MAX_DEPTH) {
                continue;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path child : children) {
                    queue.add(child);
                    depths.add(depth + 1);
                }
            } catch (IOException | RuntimeException ignored) {
                // unreadable folder: skip it
            }
        }
    }

    /** @return the install whose home is {@code dir}, or null when it is not a Java home. */
    static Install read(Path dir) {
        Path release = dir.resolve("release");
        if (!Files.isRegularFile(release)) {
            return null;
        }
        String version = null;
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (version == null || version.isEmpty()) {
            return null;
        }
        Path bin = dir.resolve("bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path exe = windows ? bin.resolve("javaw.exe") : bin.resolve("java");
        if (!Files.isRegularFile(exe)) {
            exe = bin.resolve(windows ? "java.exe" : "java");
            if (!Files.isRegularFile(exe)) {
                return null; // a release file without a runnable java (a source tree, a broken install)
            }
        }
        return new Install(dir.toAbsolutePath().normalize(), exe.toAbsolutePath().normalize(), version);
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.equals(b);
        }
    }
}

package com.tinsl.nitro.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Starts the Java setup helper from the client notice. */
public final class JavaSetupLauncher {
    private JavaSetupLauncher() {}

    public static void schedule(Path gameDir) throws IOException {
        Path game = gameDir.toAbsolutePath().normalize();
        Path instance = game.getParent();
        String name = game.getFileName().toString();
        if (!"Prism Launcher".equals(LauncherHint.detect(game, System.getProperty("sun.java.command"),
            System.getProperty("minecraft.launcher.brand")).launcher())
            || (!name.equals("minecraft") && !name.equals(".minecraft"))
            || !Files.isRegularFile(instance.resolve("instance.cfg"))
            || !Files.isRegularFile(instance.resolve("mmc-pack.json"))) {
            throw new IOException("Automatic Java setup requires a Prism instance.");
        }
        if (JavaFinder.find(game).isEmpty()) {
            throw new IOException("Java 25 is no longer available.");
        }
        Path helper = NitroLaunchFiles.extract(game, "/META-INF/nitro/nitro-java-setup.jar", "java-setup");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        new ProcessBuilder(java.toString(), "-jar", helper.toString(), "--configure-prism-after-exit", instance.toString())
            .redirectErrorStream(true)
            .redirectOutput(game.resolve(".nitro").resolve("java-setup.log").toFile())
            .start();
    }
}

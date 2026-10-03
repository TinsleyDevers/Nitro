package com.tinsl.nitro.common;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Works out which launcher started the game, so Nitro can say exactly where the {@code -javaagent}
 * line goes instead of "your launcher's JVM arguments", which sends people searching.
 *
 * Launchers do not announce themselves in one standard way, so this reads the clues they leave:
 * Prism and MultiMC start the game through their own entry point class, the official launcher (and
 * the CurseForge app, which drives it) passes {@code minecraft.launcher.brand}, and the rest are
 * recognisable by where they keep instances. A wrong guess only costs a less specific sentence.
 *
 * Dependency free, like {@link NitroSettings}: compiled into both halves of Nitro.
 *
 * @param launcher  name shown to the player
 * @param steps     where the argument goes in that launcher, as one sentence
 * @param agentFile the file the argument should point at
 * @param relative  write the argument with a path relative to the game directory
 * @param shared    agentFile is outside the instance (launchers whose JVM arguments apply to every instance)
 */
public record LauncherHint(String launcher, String steps, Path agentFile, boolean relative, boolean shared) {
    public static final String AGENT_FILE = "nitro-agent.jar";

    /**
     * Where to switch this instance to Java 25 in this launcher, or null when the launcher picks Java
     * itself and the JVM argument is the only route.
     *
     * Java 24 and later ship the module resolver fix, so on Java 25 the long pause before mods load is
     * gone with no argument at all. Measured on a 1017 mod 1.21.1 pack: 101 s to the menu on Java 25
     * without the argument, 134 s on Java 21 with it, 273 s on Java 21 without it.
     */
    public String javaSteps() {
        return switch (launcher) {
            case "Prism Launcher" -> "Right-click the instance, Edit, Settings, Java tab. Tick Java installation, click Auto-detect "
                + "and pick a 25 (Download Java fetches one if none is listed), tick Skip Java compatibility checks (1.21.1 "
                + "asks for 21, but runs on 25), then close the window and restart the game.";
            case "MultiMC" -> "Right-click the instance, Edit Instance, Settings, Java tab. Tick Java installation, click Auto-detect "
                + "or Browse and pick a Java 25, tick Skip Java compatibility checks, then restart the game.";
            case "the Modrinth App" -> "Open the instance, the settings button, Java and memory: turn on Custom Java installation and "
                + "pick a Java 25 (Detect or Browse), then restart the game.";
            case "ATLauncher" -> "On the Instances tab, this instance's Settings, Java/Minecraft: set Java Path to a Java 25, then "
                + "restart the game.";
            case "the Minecraft Launcher" -> "Installations, the three dots on this installation, Edit, More options: set Java "
                + "executable to a Java 25 javaw.exe, then restart the game.";
            case "a dedicated server" -> "Start the server with a Java 25 java instead of Java 21.";
            case "the CurseForge app" -> null;
            default -> "Set this instance to use a Java 25 installation in your launcher's Java settings.";
        };
    }

    /** The JVM argument, quoted when the path has spaces (launchers strip the quotes). */
    public String argument(Path gameDir) {
        // Always forward slashes. Java takes them on Windows, and launchers do not take the other kind:
        // Prism treats a backslash in its JVM arguments box as an escape and drops it, and a JVM that
        // cannot find its -javaagent file does not start at all.
        String path = (relative ? gameDir.toAbsolutePath().normalize().relativize(agentFile) : agentFile)
            .toString().replace('\\', '/');
        return "-javaagent:" + (path.indexOf(' ') >= 0 ? '"' + path + '"' : path);
    }

    /**
     * @param gameDir     the game directory
     * @param javaCommand the {@code sun.java.command} system property
     * @param brand       the {@code minecraft.launcher.brand} system property, may be null
     */
    public static LauncherHint detect(Path gameDir, String javaCommand, String brand) {
        Path gameDirAbs = gameDir.toAbsolutePath().normalize();
        Path local = gameDirAbs.resolve(".nitro").resolve(AGENT_FILE);
        String command = javaCommand == null ? "" : javaCommand;
        String dir = gameDirAbs.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        String brandLower = brand == null ? "" : brand.toLowerCase(Locale.ROOT);

        if (isServer(command)) {
            return new LauncherHint("a dedicated server",
                "Add it to user_jvm_args.txt (or to the java line in your start script, before -jar) and restart the server.",
                local, true, false);
        }
        if (command.startsWith("org.prismlauncher.")) {
            return new LauncherHint("Prism Launcher",
                "Right-click the instance, Edit, Settings, then the Java tab: tick Java arguments, paste the line and restart.",
                local, false, false);
        }
        if (command.startsWith("org.multimc.")) {
            return new LauncherHint("MultiMC",
                "Right-click the instance, Edit Instance, Settings, then the Java tab: tick JVM arguments, paste the line and restart.",
                local, false, false);
        }
        if (brandLower.contains("atlauncher") || dir.contains("/atlauncher/")) {
            return new LauncherHint("ATLauncher",
                "On the Instances tab, open this instance's Settings and paste the line into Java Parameters, then restart.",
                local, false, false);
        }
        Path parent = gameDirAbs.getParent();
        if (dir.contains("/curseforge/") && parent != null && parent.getFileName() != null
            && parent.getFileName().toString().equalsIgnoreCase("Instances") && parent.getParent() != null) {
            // The CurseForge app has one set of JVM arguments for every profile. Pointing it into this
            // profile's folder would stop every other profile from starting once this one is deleted,
            // so the argument points at one shared copy beside the Instances folder instead.
            return new LauncherHint("the CurseForge app",
                "Click the gear icon at the bottom left, then Minecraft, scroll to Java Settings and paste the line into "
                    + "Additional Arguments. It applies to every profile; profiles without Nitro simply ignore it.",
                parent.getParent().resolve(AGENT_FILE), false, true);
        }
        if (dir.contains("modrinth") || brandLower.contains("theseus") || brandLower.contains("modrinth")) {
            return new LauncherHint("the Modrinth App",
                "Open the instance, click the settings button, Java and memory, turn on custom Java arguments, "
                    + "paste the line and restart.",
                local, false, false);
        }
        if (dir.contains("gdlauncher")) {
            return new LauncherHint("GDLauncher",
                "Open this instance's settings, paste the line into the Java arguments and restart.",
                local, false, false);
        }
        if (brandLower.equals("minecraft-launcher")) {
            return new LauncherHint("the Minecraft Launcher",
                "Installations, the three dots on this installation, Edit, More options: add the line to JVM arguments "
                    + "(keep what is there) and restart.",
                local, false, false);
        }
        return new LauncherHint("your launcher",
            "Add it to this instance's JVM (Java) arguments in your launcher and restart.",
            local, false, false);
    }

    private static boolean isServer(String command) {
        int i = command.indexOf("--launchTarget ");
        if (i < 0) {
            return command.contains("neoforgeserver") || command.contains("forgeserver");
        }
        String target = command.substring(i + "--launchTarget ".length()).split(" ", 2)[0];
        return target.toLowerCase(Locale.ROOT).contains("server");
    }
}

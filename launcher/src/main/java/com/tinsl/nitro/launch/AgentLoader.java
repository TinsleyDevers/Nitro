package com.tinsl.nitro.launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.module.ModuleFinder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Loads Nitro's agent during early mod discovery, before the game module layer is resolved. */
final class AgentLoader {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final String AGENT_RESOURCE = "/META-INF/nitro/nitro-agent.jar";
    private static final String STUB_RESOURCE = "/META-INF/nitro/nitro-agent-stub.jar";
    static final String STUB_FILE = "nitro-agent.jar";
    private static final String PROP_LOADED = "nitro.agentLoaded";
    private static final String PROP_PATCHED = "nitro.resolverPatched";
    private static final String PROP_ERROR = "nitro.resolverPatchError";
    /** Set when the agent is not loaded, to the exact JVM argument that would load it. */
    static final String PROP_HINT = "nitro.agentArgument";
    /** Which launcher Nitro thinks started the game, and where the argument goes in it. */
    static final String PROP_LAUNCHER = "nitro.agentLauncher";
    static final String PROP_STEPS = "nitro.agentSteps";
    /** Set when switching this instance to Java 25 is an option, to where that is done in the launcher. */
    static final String PROP_JAVA_STEPS = "nitro.javaSteps";
    /** Set when a Java 25 install was found on this computer, to the program to point the launcher at. */
    static final String PROP_JAVA_25 = "nitro.java25";
    /** Temurin, the build Prism and most launchers download themselves. */
    static final String JAVA_25_DOWNLOAD = "https://adoptium.net/temurin/releases/?version=25";

    private static Instrumentation instrumentation;

    private AgentLoader() {}

    /** @return the agent instrumentation, or null when neither startup route succeeded. */
    static synchronized Instrumentation load(Path gameDir, boolean autoAttach) {
        if (instrumentation != null) {
            return instrumentation;
        }
        Path stub = gameDir.resolve(".nitro").resolve(STUB_FILE).toAbsolutePath().normalize();
        LauncherHint hint = LauncherHint.detect(gameDir, System.getProperty("sun.java.command", ""),
            System.getProperty("minecraft.launcher.brand"));
        try {
            writeStub(stub);
            if (hint.shared()) {
                // One copy every profile of this launcher can point at; see LauncherHint.
                writeStub(hint.agentFile());
            }
        } catch (IOException e) {
            LOGGER.warn("Nitro: could not write the agent loader: {}", e.toString());
        }
        removeAttachLeftovers(gameDir.resolve(".nitro"));

        boolean explicit = Boolean.getBoolean(PROP_LOADED);
        long start = System.nanoTime();
        if (!explicit && autoAttach) {
            attach(gameDir);
        }

        if (!Boolean.getBoolean(PROP_LOADED)) {
            String argument = hint.argument(gameDir.toAbsolutePath().normalize());
            System.setProperty(PROP_HINT, argument);
            System.setProperty(PROP_LAUNCHER, hint.launcher());
            System.setProperty(PROP_STEPS, hint.steps());
            // On Java 21 to 23 the module resolver pause is most of what the agent removes, and Java 24+
            // has that fix built in, so switching Java is the other way to get it (and measured faster).
            String javaSteps = Runtime.version().feature() < 24 ? hint.javaSteps() : null;
            String java25 = null;
            if (javaSteps != null) {
                System.setProperty(PROP_JAVA_STEPS, javaSteps);
                // Most players already have one (Prism downloads Java 25 for newer Minecraft versions), and
                // "use this one" is a much smaller step than "download Java and find where it went".
                try {
                    List<JavaFinder.Install> installs = JavaFinder.find(gameDir);
                    if (!installs.isEmpty()) {
                        java25 = installs.get(0).executable().toString();
                        System.setProperty(PROP_JAVA_25, java25);
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("Nitro: looking for a Java 25 install failed: {}", e.toString());
                }
            }
            LOGGER.info("Nitro: launch patches are unavailable this run. Automatic attach is {}. "
                + "The explicit JVM argument is {}. {}: {}",
                autoAttach ? "unavailable on this runtime" : "off in this instance", argument, hint.launcher(), hint.steps());
            if (javaSteps != null) {
                LOGGER.info("Nitro: Java 25 includes the fast module resolver. {}",
                    java25 == null ? "Download: " + JAVA_25_DOWNLOAD : "Installed Java 25: " + java25);
            }
            return null;
        }
        try {
            // The boot classes are in the bootstrap loader's unnamed module; our named module has
            // to read it before it may touch them (directly or reflectively).
            Class<?> boot = Class.forName("nitro.agent.boot.NitroBoot", true, null);
            AgentLoader.class.getModule().addReads(boot.getModule());
            instrumentation = (Instrumentation) boot.getField("INSTRUMENTATION").get(null);
            String ms = explicit ? System.getProperty("nitro.agentStartMillis", "?")
                : Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            LOGGER.info("Nitro: agent loaded {} ({} ms)", explicit ? "from the JVM arguments" : "by automatic attach", ms);
            if (Boolean.getBoolean(PROP_PATCHED)) {
                LOGGER.info("Nitro: patched Java's module resolver with the JDK 25 algorithm");
            } else if (Runtime.version().feature() >= 24) {
                LOGGER.info("Nitro: Java {} already ships the fast module resolver", Runtime.version().feature());
            } else if (System.getProperty(PROP_ERROR) != null) {
                LOGGER.warn("Nitro: the module resolver patch failed: {}", System.getProperty(PROP_ERROR));
            }
            return instrumentation;
        } catch (Throwable t) {
            LOGGER.warn("Nitro: agent loaded but could not be reached; loader patches disabled", t);
            return null;
        }
    }

    private static void attach(Path gameDir) {
        if (ModuleFinder.ofSystem().find("jdk.attach").isEmpty()) {
            LOGGER.info("Nitro: automatic attach unavailable because this Java installation has no jdk.attach module");
            return;
        }
        Path log = gameDir.resolve(".nitro").resolve("attach.log");
        Process helper = null;
        try {
            Path agent = NitroLaunchFiles.extract(gameDir, AGENT_RESOURCE, "nitro-attach-agent");
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            helper = new ProcessBuilder(java.toString(), "--add-modules", "jdk.attach", "-cp",
                agent.toString(), "nitro.agent.Attach", gameDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
            if (!helper.waitFor(8, TimeUnit.SECONDS)) {
                LOGGER.warn("Nitro: automatic attach timed out. See {}", log);
            } else if (helper.exitValue() != 0 || !Boolean.getBoolean(PROP_LOADED)) {
                LOGGER.warn("Nitro: Java did not load the agent by automatic attach. See {}", log);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Nitro: could not start the automatic attach helper: {}", e.toString());
        } finally {
            if (helper != null && helper.isAlive()) {
                helper.destroyForcibly();
            }
        }
    }

    /**
     * Writes the stub when it is missing or differs from the one in this jar. When the agent is running
     * the file is held open by the JVM, and on Windows cannot be replaced; that is fine, since the stub's
     * job does not change between versions, so a failed replace is not reported.
     */
    private static void writeStub(Path stub) throws IOException {
        byte[] bytes;
        try (InputStream in = AgentLoader.class.getResourceAsStream(STUB_RESOURCE)) {
            if (in == null) {
                throw new IOException("embedded resource " + STUB_RESOURCE + " missing from Nitro jar");
            }
            bytes = in.readAllBytes();
        }
        if (Files.isRegularFile(stub) && Arrays.equals(Files.readAllBytes(stub), bytes)) {
            return;
        }
        Files.createDirectories(stub.getParent());
        Path tmp = stub.resolveSibling(STUB_FILE + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, stub, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException inUse) {
            Files.deleteIfExists(tmp);
        }
    }

    /** Nitro 1.0 and 1.1 used a different filename for their extracted agents. */
    private static void removeAttachLeftovers(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "nitro-agent-*.jar")) {
            for (Path p : old) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // in use by another running instance on the old version
                }
            }
        } catch (IOException ignored) {
            // nothing to clean
        }
    }
}

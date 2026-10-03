package com.tinsl.nitro.launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Picks up Nitro's JVM agent when the player has added it to the JVM arguments, and explains how to
 * add it when they have not.
 *
 * Nitro does not attach an agent to the game on its own. Agent access lets code rewrite any class,
 * the JDK's included, and Java only hands that out to agents named on the command line, which is to
 * say with the player's say-so. So the agent runs only from
 * {@code -javaagent:<game dir>/.nitro/nitro-agent.jar}. That file is a small stub that never changes
 * between Nitro versions (see {@code nitro.stub.NitroAgentStub}); Nitro writes it here on every launch
 * where it is missing, so the argument can be added once and left alone.
 *
 * Without the agent everything that runs as a normal mod still works. What is lost is the part that
 * has to run before mods load: the module resolver back-port and the loader patches.
 */
final class AgentLoader {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
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

    private static Instrumentation instrumentation;

    private AgentLoader() {}

    /** @return the agent's Instrumentation, or null when the player has not added the agent. */
    static synchronized Instrumentation load(Path gameDir) {
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

        if (!Boolean.getBoolean(PROP_LOADED)) {
            String argument = hint.argument(gameDir.toAbsolutePath().normalize());
            System.setProperty(PROP_HINT, argument);
            System.setProperty(PROP_LAUNCHER, hint.launcher());
            System.setProperty(PROP_STEPS, hint.steps());
            // On Java 21 to 23 the module resolver pause is most of what the agent removes, and Java 24+
            // has that fix built in, so switching Java is the other way to get it (and measured faster).
            String javaSteps = Runtime.version().feature() < 24 ? hint.javaSteps() : null;
            if (javaSteps != null) {
                System.setProperty(PROP_JAVA_STEPS, javaSteps);
            }
            String error = System.getProperty(PROP_ERROR);
            if (error != null) {
                LOGGER.warn("Nitro: the agent is in the JVM arguments but did not start ({}). Launch patches are off.", error);
            } else if (javaSteps != null) {
                String nl = System.lineSeparator();
                LOGGER.warn("Nitro: this launch included a long pause before any mod loaded: Java {}'s module resolver is slow with "
                    + "hundreds of mod jars. Two ways to remove it:{}"
                    + "  1. Run this instance on Java 25, which has the fix built in and needs nothing else (fastest). {}: {}{}"
                    + "  2. Or keep this Java and add this line to the JVM arguments once (it stays valid when Nitro updates):{}"
                    + "     {}{}     {}: {}{}Everything else in Nitro is running either way.",
                    Runtime.version().feature(), nl, hint.launcher(), javaSteps, nl, nl, argument, nl, hint.launcher(), hint.steps(), nl);
            } else if (Runtime.version().feature() >= 24) {
                LOGGER.info("Nitro: running on Java {}, which already has the fast module resolver, so the Nitro agent is "
                    + "optional here (it adds Mixin and jar lookup patches worth about a second on a 1000 mod pack). "
                    + "To use it anyway: {}", Runtime.version().feature(), argument);
            } else {
                String nl = System.lineSeparator();
                LOGGER.warn("Nitro: launch patches are off because the Nitro agent is not in the JVM arguments. "
                    + "Nitro does not load it by itself. To turn them on, add this line once (it stays valid when Nitro updates):"
                    + "{}    {}{}Started from {}: {}{}Everything else in Nitro is running.",
                    nl, argument, nl, hint.launcher(), hint.steps(), nl);
            }
            return null;
        }
        try {
            // The boot classes are in the bootstrap loader's unnamed module; our named module has
            // to read it before it may touch them (directly or reflectively).
            Class<?> boot = Class.forName("nitro.agent.boot.NitroBoot", true, null);
            AgentLoader.class.getModule().addReads(boot.getModule());
            instrumentation = (Instrumentation) boot.getField("INSTRUMENTATION").get(null);
            String ms = System.getProperty("nitro.agentStartMillis", "?");
            int feature = Runtime.version().feature();
            if (Boolean.getBoolean(PROP_PATCHED)) {
                LOGGER.info("Nitro: agent loaded from the JVM arguments ({} ms); hot-patched java.lang.module.Resolver#makeGraph "
                    + "with the JDK 25 algorithm (removes the multi-minute module resolution pause caused by 1000+ automatic modules)", ms);
            } else if (feature >= 24) {
                LOGGER.info("Nitro: agent loaded from the JVM arguments ({} ms); Java {} already ships the fast module resolver", ms, feature);
            } else {
                LOGGER.warn("Nitro: agent loaded but the module resolver patch was NOT applied ({}). "
                    + "Startup will include the long module-resolution pause.", System.getProperty(PROP_ERROR, "off in config"));
            }
            return instrumentation;
        } catch (Throwable t) {
            LOGGER.warn("Nitro: agent loaded but could not be reached; loader patches disabled", t);
            return null;
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

    /** Nitro 1.0 and 1.1 extracted the agent next to a log from the helper that attached it. Neither is used now. */
    private static void removeAttachLeftovers(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "{nitro-agent-*.jar,attach.log}")) {
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

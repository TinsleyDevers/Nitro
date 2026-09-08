package com.tinsl.nitro.launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gets the embedded JVM agent into the running game and hands back its {@link Instrumentation}.
 *
 * Why a child process: HotSpot refuses to attach to itself unless
 * {@code -Djdk.attach.allowAttachSelf=true} is passed on the command line, and Nitro must work with
 * an unmodified launcher profile. A helper JVM attaches to us instead (same trick Byte Buddy uses).
 *
 * The agent itself (see {@code nitro.agent.NitroAgent}) publishes the Instrumentation in a
 * bootstrap-class-path holder and, on Java 21-23, installs the JDK 25 module-resolver algorithm.
 */
final class AgentLoader {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final String AGENT_RESOURCE = "/META-INF/nitro/nitro-agent.jar";
    private static final String PROP_LOADED = "nitro.agentLoaded";
    private static final String PROP_PATCHED = "nitro.resolverPatched";
    private static final String PROP_ERROR = "nitro.resolverPatchError";

    private static Instrumentation instrumentation;

    private AgentLoader() {}

    /** @return the agent's Instrumentation, or null when the agent could not be loaded. */
    static synchronized Instrumentation load(Path gameDir) {
        if (instrumentation != null) {
            return instrumentation;
        }
        long start = System.nanoTime();
        try {
            if (!Boolean.getBoolean(PROP_LOADED)) {
                attach(gameDir);
            }
            if (!Boolean.getBoolean(PROP_LOADED)) {
                return null;
            }
            // The boot classes are in the bootstrap loader's unnamed module; our named module has
            // to read it before it may touch them (directly or reflectively).
            Class<?> boot = Class.forName("nitro.agent.boot.NitroBoot", true, null);
            AgentLoader.class.getModule().addReads(boot.getModule());
            instrumentation = (Instrumentation) boot.getField("INSTRUMENTATION").get(null);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            int feature = Runtime.version().feature();
            if (Boolean.getBoolean(PROP_PATCHED)) {
                LOGGER.info("Nitro: agent attached in {} ms; hot-patched java.lang.module.Resolver#makeGraph with the JDK 25 "
                    + "algorithm (removes the multi-minute module resolution pause caused by 1000+ automatic modules)", ms);
            } else if (feature >= 24) {
                LOGGER.info("Nitro: agent attached in {} ms; Java {} already ships the fast module resolver", ms, feature);
            } else {
                String err = System.getProperty(PROP_ERROR, "unknown");
                LOGGER.warn("Nitro: agent attached but the module resolver patch was NOT applied ({}). "
                    + "Startup will include the long module-resolution pause.", err);
            }
            return instrumentation;
        } catch (Throwable t) {
            LOGGER.warn("Nitro: failed to load the JVM agent; loader patches disabled", t);
            return null;
        }
    }

    private static void attach(Path gameDir) throws Exception {
        Path agentJar = NitroLaunchFiles.extract(gameDir, AGENT_RESOURCE, "nitro-agent");
        Path javaExe = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        if (!Files.isRegularFile(javaExe)) {
            LOGGER.warn("Nitro: cannot find java executable at {}, agent not loaded", javaExe);
            return;
        }
        String pid = Long.toString(ProcessHandle.current().pid());
        Path log = agentJar.resolveSibling("attach.log");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.add("-XX:TieredStopAtLevel=1");
        cmd.add("-Xshare:auto");
        cmd.add("-XX:+UseSerialGC");
        cmd.add("-Xmx64m");
        cmd.add("-cp");
        cmd.add(agentJar.toString());
        cmd.add("nitro.agent.Attach");
        cmd.add(pid);
        cmd.add(agentJar.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Process process = pb.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            LOGGER.warn("Nitro: attach helper timed out, agent not loaded (see {})", log);
            return;
        }
        if (!Boolean.getBoolean(PROP_LOADED)) {
            LOGGER.warn("Nitro: attach helper exited with code {} but the agent did not load (error: {}). Details: {}",
                process.exitValue(), System.getProperty(PROP_ERROR, "unknown"), log);
            LOGGER.warn("Nitro: workaround: add -javaagent:{} to the JVM arguments", agentJar);
        }
    }
}

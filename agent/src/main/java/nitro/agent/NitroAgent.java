package nitro.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Both dynamic attach and the explicit JVM startup stub install the same bootstrap hooks.
 *
 * It does two things:
 * <ol>
 *   <li>Publishes the {@link Instrumentation} through {@code nitro.agent.boot.NitroBoot} so the
 *       launcher half can patch loader-level classes (Mixin, Secure Jar Handler).</li>
 *   <li>On Java 21-23 installs a hot patch for {@code java.lang.module.Resolver#makeGraph}.
 *       JDK 21's implementation of that method is O(n^3) in the number of automatic modules. Every
 *       mod jar without a module-info is an automatic module, so a 1000-mod pack spends minutes
 *       inside that single method before any mod code runs. JDK 25 fixed the algorithm with a
 *       one-line guard; this agent back-ports that fix.</li>
 * </ol>
 */
public final class NitroAgent {
    public static final String PROP_LOADED = "nitro.agentLoaded";
    public static final String PROP_PATCHED = "nitro.resolverPatched";
    public static final String PROP_ERROR = "nitro.resolverPatchError";

    private NitroAgent() {}

    /** Entry point for the Attach API. The JVM needs the JarFile for subsequent bootstrap lookups. */
    public static void agentmain(String gameDir, Instrumentation inst) throws Exception {
        if (!Boolean.getBoolean(PROP_LOADED)) {
            Path jar = Path.of(NitroAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jar.toFile()));
            System.clearProperty(PROP_ERROR);
            install(inst, gameDir);
        }
    }

    /**
     * @param inst    the Instrumentation the JVM gave the stub
     * @param gameDir the game directory, for reading config/nitro.properties
     */
    public static void install(Instrumentation inst, String gameDir) {
        if (Boolean.getBoolean(PROP_LOADED)) {
            return;
        }
        try {
            Class<?> boot = Class.forName("nitro.agent.boot.NitroBoot", true, null);
            boot.getField("INSTRUMENTATION").set(null, inst);
            boot.getField("JAVA_FEATURE").setInt(null, Runtime.version().feature());
            System.setProperty(PROP_LOADED, "true");
        } catch (Throwable t) {
            System.setProperty(PROP_ERROR, "agent bootstrap failed: " + t);
            System.err.println("[Nitro] Failed to bootstrap agent: " + t);
            return;
        }

        if (Runtime.version().feature() >= 24 || Boolean.getBoolean(PROP_PATCHED)) {
            return; // the JDK already has the fast resolver (or we were loaded twice)
        }
        if (!resolverPatchEnabled(gameDir)) {
            return;
        }
        try {
            Class<?> patch = Class.forName("nitro.agent.boot.ResolverPatch", true, null);
            Module bootUnnamed = patch.getModule();
            Module javaBase = Object.class.getModule();
            inst.redefineModule(
                javaBase,
                Set.of(bootUnnamed),
                Map.of("jdk.internal.org.objectweb.asm", Set.of(bootUnnamed)),
                Map.of("java.lang.module", Set.of(bootUnnamed)),
                Set.of(),
                Map.of()
            );
            Method apply = patch.getMethod("apply", Instrumentation.class);
            Object ok = apply.invoke(null, inst);
            if (Boolean.TRUE.equals(ok)) {
                System.setProperty(PROP_PATCHED, "true");
            } else {
                System.setProperty(PROP_ERROR, "transformer did not modify java.lang.module.Resolver");
            }
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause instanceof java.lang.reflect.InvocationTargetException) {
                cause = cause.getCause();
            }
            System.setProperty(PROP_ERROR, cause.toString());
            System.err.println("[Nitro] Failed to install module resolver patch: " + cause);
        }
    }

    /**
     * The agent runs before any of Nitro's other code, so it reads the one setting it needs itself:
     * {@code -Dnitro.config.resolverPatch} first, then {@code resolverPatch} in the config file,
     * defaulting to on like the rest of Nitro.
     */
    private static boolean resolverPatchEnabled(String gameDir) {
        String override = System.getProperty("nitro.config.resolverPatch");
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        try {
            Path file = Paths.get(gameDir, "config", "nitro.properties");
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
                String v = p.getProperty("resolverPatch");
                if (v != null) {
                    return Boolean.parseBoolean(v.trim());
                }
            }
        } catch (Throwable ignored) {
            // unreadable config: default
        }
        return true;
    }
}

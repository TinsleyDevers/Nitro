package nitro.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Entry point of the Nitro JVM agent.
 *
 * The agent is loaded into the game JVM (either dynamically through the attach API by
 * {@code com.tinsl.nitro.launch.AgentLoader}, or statically via {@code -javaagent}) and does two
 * things:
 * <ol>
 *   <li>Publishes its {@link Instrumentation} through {@code nitro.agent.boot.NitroBoot} so the
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

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation inst) {
        if (Boolean.getBoolean(PROP_LOADED)) {
            return;
        }
        try {
            Path self = Paths.get(NitroAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // Make the boot classes visible to java.base (and everything else) by putting them on the
            // bootstrap search path.
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self.toFile()));
            Class<?> boot = Class.forName("nitro.agent.boot.NitroBoot", true, null);
            boot.getField("INSTRUMENTATION").set(null, inst);
            boot.getField("JAVA_FEATURE").setInt(null, Runtime.version().feature());
            System.setProperty(PROP_LOADED, "true");
        } catch (Throwable t) {
            System.setProperty(PROP_ERROR, "agent bootstrap failed: " + t);
            System.err.println("[Nitro] Failed to bootstrap agent: " + t);
            t.printStackTrace();
            return;
        }

        if (Runtime.version().feature() >= 24 || Boolean.getBoolean(PROP_PATCHED)) {
            return; // the JDK already has the fast resolver (or we were loaded twice)
        }
        if (!Boolean.parseBoolean(System.getProperty("nitro.resolverPatch", "true"))) {
            return; // disabled in config/nitro.properties (the launcher mirrors the setting into this property)
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
            cause.printStackTrace();
        }
    }
}

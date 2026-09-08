package nitro.agent.boot;

import java.lang.instrument.Instrumentation;

/**
 * Lives on the bootstrap class path. Holds the {@link Instrumentation} the agent received so that
 * Nitro's launcher half (which runs inside ModLauncher's module layers) can install further
 * class-file patches after the agent has attached. Bootstrap classes are visible from every class
 * loader, which is what makes this hand-over possible without any shared class path.
 */
public final class NitroBoot {
    public static volatile Instrumentation INSTRUMENTATION;
    /** Java feature version the agent was loaded into (for logging / decisions in the launcher). */
    public static volatile int JAVA_FEATURE;

    private NitroBoot() {}
}

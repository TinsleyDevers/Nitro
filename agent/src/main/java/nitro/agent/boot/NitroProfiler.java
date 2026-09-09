package nitro.agent.boot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples the loading threads during launch and says where the time went.
 *
 * After the launch patches there is not much left in Nitro's own reach: on a 1000 mod pack the
 * remaining minutes are the mods' own constructors, their setup work and, on a surprising number of
 * packs, blocking network calls made while the game loads (supporter lists, update checks, cosmetic
 * downloads). None of that is Nitro's to fix, but a player staring at a loading screen has no way of
 * telling which mod is responsible. This finds out, cheaply, and prints it once.
 *
 * Every quarter of a second or so the profiler takes one snapshot of all running threads and, for
 * each one, walks its stack from the top down to the first frame that belongs to a mod module.
 * That frame is charged one sample. A stack that also passes through a socket read, a connect or a
 * DNS lookup is charged to the network bucket as well, under the same mod. Nothing is instrumented
 * and no state is kept per call, so the cost is the snapshot itself, well under a percent of launch.
 *
 * This class lives on the bootstrap class path (the agent puts it there), so it uses nothing outside
 * java.base and hands its result back as plain strings for the mod half to log.
 */
public final class NitroProfiler {
    private static final long DEFAULT_INTERVAL_MS = 500L;

    /**
     * Modules that are the game and Nitro itself. Time inside these says nothing useful: the
     * interesting frame is always further up the stack, in whoever called them.
     */
    private static final List<String> PLATFORM = List.of(
        "minecraft", "neoforge", "nitro", "javafmllanguage", "lowcodelanguage", "mclanguage"
    );

    /**
     * Prefixes of module names that belong to the JDK. Everything else, mod or library, is worth
     * attributing: a library can be the slow thing (the login check inside authlib, for one), and
     * the report says which entries are mods rather than hiding the rest.
     */
    private static final String[] JDK_MODULES = {"java.", "jdk.", "sun.", "javafx."};

    /** Class name prefixes that mean the thread is waiting on the network rather than working. */
    private static final String[] NETWORK = {
        "java.net.", "sun.net.", "sun.nio.ch.Net", "sun.nio.ch.SocketChannelImpl",
        "sun.nio.ch.NioSocketImpl", "jdk.internal.net.", "javax.net.ssl.", "sun.security.ssl.",
        "java.nio.channels.SocketChannel", "okhttp3.", "org.apache.http.", "org.apache.hc.",
        "jdk.internal.foreign", "com.sun.net."
    };

    private static final Map<String, AtomicLong> CPU = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> NET = new ConcurrentHashMap<>();
    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();

    private static volatile Thread sampler;
    private static volatile long intervalMs = DEFAULT_INTERVAL_MS;
    private static volatile long startedAtNanos;
    private static volatile boolean stopped;

    private NitroProfiler() {}

    /** Starts sampling. Safe to call twice; the second call does nothing. */
    public static synchronized void start() {
        if (sampler != null || stopped) {
            return;
        }
        startedAtNanos = System.nanoTime();
        Thread t = new Thread(NitroProfiler::run, "Nitro startup profiler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        sampler = t;
        t.start();
    }

    /** Give up on our own after this long, in case nothing ever calls stop(). */
    private static final long MAX_RUN_MS = 20 * 60 * 1000L;

    private static void run() {
        long deadline = System.currentTimeMillis() + MAX_RUN_MS;
        while (!stopped) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            if (stopped || System.currentTimeMillis() > deadline) {
                return;
            }
            try {
                sample();
            } catch (Throwable ignored) {
                // a diagnostic must never be the reason a launch fails
            }
        }
    }

    private static void sample() {
        long begin = System.nanoTime();
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        SNAPSHOT_NANOS.addAndGet(System.nanoTime() - begin);
        TICKS.incrementAndGet();
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == sampler || thread.getState() != Thread.State.RUNNABLE) {
                continue;
            }
            StackTraceElement[] stack = entry.getValue();
            if (stack.length == 0) {
                continue;
            }
            boolean onNetwork = false;
            String owner = null;
            for (StackTraceElement frame : stack) {
                if (!onNetwork && isNetwork(frame.getClassName())) {
                    onNetwork = true;
                }
                if (owner == null) {
                    String module = frame.getModuleName();
                    if (isAttributable(module)) {
                        owner = module;
                    }
                }
                if (owner != null && onNetwork) {
                    break;
                }
            }
            if (owner == null) {
                owner = onNetwork ? "(game or loader)" : null;
            }
            if (owner == null) {
                continue;
            }
            CPU.computeIfAbsent(owner, k -> new AtomicLong()).incrementAndGet();
            if (onNetwork) {
                NET.computeIfAbsent(owner, k -> new AtomicLong()).incrementAndGet();
            }
        }
    }

    private static boolean isNetwork(String className) {
        for (String prefix : NETWORK) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAttributable(String module) {
        if (module == null || module.isEmpty()) {
            return false;
        }
        for (String prefix : JDK_MODULES) {
            if (module.startsWith(prefix)) {
                return false;
            }
        }
        return !PLATFORM.contains(module);
    }

    /**
     * Stops sampling and renders the result.
     *
     * @param modModules module names and mod ids of the installed mods, used only to mark which
     *                    entries are mods; pass an empty set to skip the marking
     * @return log-ready lines, empty when the profiler never ran or saw nothing worth reporting
     */
    public static synchronized List<String> stop(java.util.Set<String> modModules) {
        stopped = true;
        Thread t = sampler;
        sampler = null;
        if (t != null) {
            t.interrupt();
        }
        long ticks = TICKS.get();
        if (ticks < 8) {
            return List.of();
        }

        double sampleSeconds = intervalMs / 1000.0;
        double wallSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        Map<String, Long> cpu = snapshot(CPU);
        Map<String, Long> net = snapshot(NET);

        double totalThreadSeconds = 0.0;
        for (long v : cpu.values()) {
            totalThreadSeconds += v * sampleSeconds;
        }
        double totalNetSeconds = 0.0;
        for (long v : net.values()) {
            totalNetSeconds += v * sampleSeconds;
        }

        List<String> out = new ArrayList<>();
        out.add(String.format("where launch time went (sampled every %d ms over %.0f s; %.0f thread-seconds attributed)",
            intervalMs, wallSeconds, totalThreadSeconds));

        List<Map.Entry<String, Long>> top = new ArrayList<>(cpu.entrySet());
        top.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed());
        int shown = 0;
        for (Map.Entry<String, Long> e : top) {
            double seconds = e.getValue() * sampleSeconds;
            if (seconds < 1.0 || shown >= 12) {
                break;
            }
            long netSamples = net.getOrDefault(e.getKey(), 0L);
            String suffix = netSamples > 0
                ? String.format("  (%.1f s of it waiting on the network)", netSamples * sampleSeconds)
                : "";
            String name = e.getKey();
            if (!modModules.isEmpty() && !modModules.contains(name) && !name.startsWith("(")) {
                name = name + " [not a mod]";
            }
            out.add(String.format("  %-38s %6.1f s%s", name, seconds, suffix));
            shown++;
        }
        if (shown == 0) {
            return List.of();
        }

        if (totalNetSeconds >= 5.0) {
            out.add(String.format("  %.1f thread-seconds of that was blocking network calls made while the game loaded "
                + "(supporter lists, update checks, cosmetics). Nothing in Nitro can shorten those. "
                + "Save My Shaky Network blocks them per mod without changing what the mods do.", totalNetSeconds));
        }
        double cost = SNAPSHOT_NANOS.get() / 1_000_000_000.0;
        out.add(String.format("  the profiler itself cost %.2f s; set startupProfiler=false in config/nitro.properties to skip it",
            cost));
        return out;
    }

    private static Map<String, Long> snapshot(Map<String, AtomicLong> counters) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, AtomicLong> e : counters.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }
}

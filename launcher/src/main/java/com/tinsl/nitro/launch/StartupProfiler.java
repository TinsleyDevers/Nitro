package com.tinsl.nitro.launch;

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
 * It runs in Nitro's launcher half from the first moment Nitro loads, and needs nothing from the JVM
 * agent, so every player gets it. The mod half asks for the result by name when loading finishes
 * (see {@code NitroReport}) and logs it; this class hands it back as plain strings.
 */
public final class StartupProfiler {
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

    /**
     * Modules that load and transform classes. A thread in here is doing work on behalf of whatever
     * is further down its stack (the mod whose code needed the class), so these are stepped over
     * when looking for who to charge. Charging the loader itself would hide every mod behind it.
     */
    private static final List<String> LOADER = List.of(
        "cpw.mods.securejarhandler", "cpw.mods.modlauncher", "cpw.mods.bootstraplauncher", "fml_loader",
        "org.spongepowered.mixin", "mixinextras", "org.objectweb.asm", "org.objectweb.asm.tree",
        "org.objectweb.asm.commons", "org.objectweb.asm.util", "org.objectweb.asm.tree.analysis",
        "org.apache.logging.log4j", "org.apache.logging.log4j.core", "com.google.common", "com.google.gson"
    );

    /** Charged when a thread is loading classes and no mod is on its stack to charge instead. */
    private static final String LOADING = "(class loading, no mod on the stack)";

    /**
     * Methods a thread sits in while it waits for something to arrive: a listener in accept(), a
     * selector with nothing ready. That is an idle thread, not a launch being held up, and counting
     * it as time "on the network" would bury the real waits.
     */
    private static final List<String> IDLE_WAITS = List.of(
        "accept", "accept0", "implAccept", "select", "selectNow", "doSelect", "poll", "poll0", "wait",
        "epollWait", "dequeueStatus", "park", "getQueuedCompletionStatus", "read0Blocking"
    );

    /** Everything one thread was seen doing, kept apart until the end so idle threads can be dropped whole. */
    private static final class ThreadStats {
        long samples;
        long netSamples;
        final Map<String, long[]> owners = new HashMap<>(); // owner -> {samples, network samples}
    }

    private static final Map<Long, ThreadStats> THREADS = new ConcurrentHashMap<>();
    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();

    private static volatile Thread sampler;
    private static volatile long intervalMs = DEFAULT_INTERVAL_MS;
    private static volatile long startedAtNanos;
    private static volatile boolean stopped;

    private StartupProfiler() {}

    /** Starts sampling. Safe to call twice; the second call does nothing. */
    public static synchronized void start() {
        if (sampler != null || stopped) {
            return;
        }
        startedAtNanos = System.nanoTime();
        Thread t = new Thread(StartupProfiler::run, "Nitro startup profiler");
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
            if (idle(stack)) {
                continue;
            }
            boolean onNetwork = false;
            boolean inLoader = false;
            String owner = null;
            for (StackTraceElement frame : stack) {
                if (!onNetwork && isNetwork(frame.getClassName())) {
                    onNetwork = true;
                }
                if (owner == null) {
                    String module = frame.getModuleName();
                    if (module != null && LOADER.contains(module)) {
                        inLoader = true;
                    } else if (isAttributable(module)) {
                        owner = module;
                    }
                }
                if (owner != null && onNetwork) {
                    break;
                }
            }
            if (owner == null) {
                owner = inLoader ? LOADING : onNetwork ? "(game or loader)" : null;
            }
            if (owner == null) {
                continue;
            }
            ThreadStats stats = THREADS.computeIfAbsent(thread.threadId(), k -> new ThreadStats());
            stats.samples++;
            long[] counts = stats.owners.computeIfAbsent(owner, k -> new long[2]);
            counts[0]++;
            if (onNetwork) {
                stats.netSamples++;
                counts[1]++;
            }
        }
    }

    /** True when the top of the stack is a thread waiting for input that may never come. */
    private static boolean idle(StackTraceElement[] stack) {
        int depth = Math.min(stack.length, 6);
        for (int i = 0; i < depth; i++) {
            StackTraceElement frame = stack[i];
            String cls = frame.getClassName();
            if ((cls.startsWith("sun.nio.ch.") || cls.startsWith("java.net.") || cls.startsWith("sun.net.")
                || cls.startsWith("jdk.internal.misc.") || cls.startsWith("io.netty.")) && IDLE_WAITS.contains(frame.getMethodName())) {
                return true;
            }
        }
        return false;
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
            try {
                // Let a sample that is in flight finish, so the per-thread tallies are not read mid-update.
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long ticks = TICKS.get();
        if (ticks < 8) {
            return List.of();
        }

        double sampleSeconds = intervalMs / 1000.0;
        double wallSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        Map<String, Long> cpu = new HashMap<>();
        Map<String, Long> net = new HashMap<>();
        for (ThreadStats stats : THREADS.values()) {
            // A thread that spent nearly all of a long life inside a socket is a connection being kept
            // open (a chat link, a metrics client), not something the launch was waiting for.
            boolean keptOpen = stats.samples >= ticks / 2 && stats.netSamples * 10 >= stats.samples * 9;
            if (keptOpen) {
                continue;
            }
            for (Map.Entry<String, long[]> e : stats.owners.entrySet()) {
                cpu.merge(e.getKey(), e.getValue()[0], Long::sum);
                if (e.getValue()[1] > 0) {
                    net.merge(e.getKey(), e.getValue()[1], Long::sum);
                }
            }
        }

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

}

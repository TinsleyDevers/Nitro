package com.tinsl.nitro.launch;

import nitro.agent.boot.NitroHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Index over Mixin's registered configs (see {@link LoaderPatches}): target class name -> configs,
 * and mixin package prefix -> configs. Rebuilt whenever the config list grows or a config is
 * (re)prepared. Answers are supersets of what the original per-config loop would accept, so the
 * original checks still run, just on a handful of configs instead of all of them.
 */
final class MixinConfigIndexer implements NitroHooks.ConfigIndexer {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");
    private static final List<?> NONE = Collections.emptyList();

    private final Field mixinMapping;      // MixinConfig: Map<String, List<MixinInfo>>
    private final Method getMixinPackage;  // MixinConfig: String getMixinPackage()
    private volatile Snapshot snapshot;

    MixinConfigIndexer(Class<?> mixinConfigClass) throws ReflectiveOperationException {
        mixinMapping = mixinConfigClass.getDeclaredField("mixinMapping");
        mixinMapping.setAccessible(true);
        getMixinPackage = mixinConfigClass.getMethod("getMixinPackage");
    }

    private static final class Snapshot {
        final List<?> source;
        final int size;
        final long version;
        final Object[] configs;
        final Map<String, int[]> byTarget = new HashMap<>(1 << 16);
        final Map<String, int[]> byPackage = new HashMap<>();
        final List<Object[]> loosePackages = new ArrayList<>(); // {String prefix, Integer index} for prefixes without trailing dot

        Snapshot(List<?> source, long version) {
            this.source = source;
            this.size = source.size();
            this.version = version;
            this.configs = source.toArray();
        }
    }

    @Override
    public List<?> configsFor(List<?> configs, String className, long version) {
        Snapshot s = snapshot;
        if (s == null || s.source != configs || s.size != configs.size() || s.version != version) {
            s = rebuild(configs, version);
        }
        int[] hits = s.byTarget.get(className);
        int[] merged = hits;
        for (int i = className.indexOf('.'); i >= 0; i = className.indexOf('.', i + 1)) {
            int[] p = s.byPackage.get(className.substring(0, i + 1));
            if (p != null) {
                merged = union(merged, p);
            }
        }
        for (Object[] loose : s.loosePackages) {
            if (className.startsWith((String) loose[0])) {
                merged = union(merged, new int[] {(Integer) loose[1]});
            }
        }
        if (merged == null) {
            return NONE;
        }
        if (merged.length == 1) {
            return List.of(s.configs[merged[0]]);
        }
        Object[] out = new Object[merged.length];
        for (int i = 0; i < merged.length; i++) {
            out[i] = s.configs[merged[i]];
        }
        return Arrays.asList(out);
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) {
            return b;
        }
        int[] r = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) {
                r[k++] = a[i++];
            } else if (a[i] > b[j]) {
                r[k++] = b[j++];
            } else {
                r[k++] = a[i++];
                j++;
            }
        }
        while (i < a.length) {
            r[k++] = a[i++];
        }
        while (j < b.length) {
            r[k++] = b[j++];
        }
        return k == r.length ? r : Arrays.copyOf(r, k);
    }

    private synchronized Snapshot rebuild(List<?> configs, long version) {
        Snapshot s = snapshot;
        if (s != null && s.source == configs && s.size == configs.size() && s.version == version) {
            return s;
        }
        long start = System.nanoTime();
        Snapshot n = new Snapshot(configs, version);
        Map<String, List<Integer>> targets = new HashMap<>(1 << 16);
        Map<String, List<Integer>> packages = new HashMap<>();
        int targetCount = 0;
        try {
            for (int i = 0; i < n.configs.length; i++) {
                Object config = n.configs[i];
                @SuppressWarnings("unchecked")
                Map<String, ?> mapping = (Map<String, ?>) mixinMapping.get(config);
                if (mapping != null) {
                    for (String target : mapping.keySet()) {
                        targets.computeIfAbsent(target, k -> new ArrayList<>(2)).add(i);
                        targetCount++;
                    }
                }
                String pkg = (String) getMixinPackage.invoke(config);
                if (pkg != null && !pkg.isEmpty()) {
                    if (pkg.endsWith(".")) {
                        packages.computeIfAbsent(pkg, k -> new ArrayList<>(2)).add(i);
                    } else {
                        n.loosePackages.add(new Object[] {pkg, i});
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        targets.forEach((k, v) -> n.byTarget.put(k, toSortedArray(v)));
        packages.forEach((k, v) -> n.byPackage.put(k, toSortedArray(v)));
        snapshot = n;
        LOGGER.debug("Nitro: indexed {} mixin configs ({} targets) in {} ms", n.configs.length, targetCount, (System.nanoTime() - start) / 1_000_000L);
        return n;
    }

    private static int[] toSortedArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        Arrays.sort(a);
        return a;
    }

    static Set<String> debugTargets(MixinConfigIndexer ix) {
        Snapshot s = ix.snapshot;
        return s == null ? Set.of() : s.byTarget.keySet();
    }
}

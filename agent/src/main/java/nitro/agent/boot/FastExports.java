package nitro.agent.boot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ResolvedModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replacement for {@code java.lang.module.Resolver#checkExportSuppliers(Map)}.
 *
 * The JDK verifies, for every module, that no two modules it reads export the same package to it.
 * Its implementation builds a fresh package-to-exporter map per module by walking the packages of
 * every module it reads. Automatic modules export all their packages and read every other
 * automatic module, so with ~1000 mod jars (about 200k packages in total) that is roughly
 * 1000 x 200k hash insertions: several seconds on one thread, right after the module graph is
 * built.
 *
 * The check is equivalent to: (a) no two automatic modules that a module reads share a package,
 * (b) no automatic module a module reads exports one of that module's own packages, and (c) the
 * qualified/unqualified exports of the (few) explicit modules do not collide with any of the
 * above. (a) can be decided once globally, (b) and (c) are linear in the number of packages. Same
 * failures, same exception (via the JDK's own {@code failTwoSuppliers}), a fraction of the work.
 */
public final class FastExports {
    private static final MethodHandle FAIL_TWO_SUPPLIERS;

    static {
        try {
            Class<?> resolver = Class.forName("java.lang.module.Resolver", false, null);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(resolver, MethodHandles.lookup());
            // instance method: (Resolver, ModuleDescriptor, String, ModuleDescriptor, ModuleDescriptor)V
            FAIL_TWO_SUPPLIERS = lookup.findVirtual(resolver, "failTwoSuppliers",
                MethodType.methodType(void.class, ModuleDescriptor.class, String.class, ModuleDescriptor.class, ModuleDescriptor.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FastExports() {}

    /** Called from the patched Resolver with the Resolver instance (needed for its failTwoSuppliers). */
    public static void checkExportSuppliers(Object resolver, Map<ResolvedModule, Set<ResolvedModule>> graph) {
        // Global view of automatic modules: package -> exporting automatic module, plus any collisions.
        Map<ModuleDescriptor, ResolvedModule> byDescriptor = new HashMap<>(graph.size() * 2);
        Map<String, ModuleDescriptor> autoPackages = new HashMap<>(1 << 18);
        List<Object[]> autoConflicts = new ArrayList<>();
        Set<ModuleDescriptor> seenAutomatic = new HashSet<>();
        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : graph.entrySet()) {
            byDescriptor.put(e.getKey().reference().descriptor(), e.getKey());
            for (ResolvedModule endpoint : e.getValue()) {
                ModuleDescriptor d2 = endpoint.reference().descriptor();
                byDescriptor.put(d2, endpoint);
                if (d2.isAutomatic() && seenAutomatic.add(d2)) {
                    for (String pn : d2.packages()) {
                        ModuleDescriptor prev = autoPackages.putIfAbsent(pn, d2);
                        if (prev != null) {
                            autoConflicts.add(new Object[] {pn, d2, prev});
                        }
                    }
                }
            }
        }

        for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : graph.entrySet()) {
            ModuleDescriptor d1 = e.getKey().reference().descriptor();
            String name1 = d1.name();
            Set<ResolvedModule> reads = e.getValue();

            // (a) two automatic modules read by d1 export the same package
            for (Object[] c : autoConflicts) {
                ModuleDescriptor d2 = (ModuleDescriptor) c[1];
                ModuleDescriptor supplier = (ModuleDescriptor) c[2];
                if (reads.contains(byDescriptor.get(d2)) && reads.contains(byDescriptor.get(supplier))) {
                    fail(resolver, d1, (String) c[0], d2, supplier);
                }
            }
            // (b) an automatic module read by d1 exports one of d1's own packages
            Set<String> own = d1.packages();
            for (String pn : own) {
                ModuleDescriptor a = autoPackages.get(pn);
                if (a != null && a != d1 && reads.contains(byDescriptor.get(a))) {
                    fail(resolver, d1, pn, a, d1);
                }
            }
            // (c) explicit modules' exports
            Map<String, ModuleDescriptor> named = null;
            for (ResolvedModule endpoint : reads) {
                ModuleDescriptor d2 = endpoint.reference().descriptor();
                if (d2.isAutomatic()) {
                    continue;
                }
                for (ModuleDescriptor.Exports export : d2.exports()) {
                    if (export.isQualified() && !export.targets().contains(name1)) {
                        continue;
                    }
                    String source = export.source();
                    if (own.contains(source)) {
                        fail(resolver, d1, source, d2, d1);
                    }
                    ModuleDescriptor a = autoPackages.get(source);
                    if (a != null && reads.contains(byDescriptor.get(a))) {
                        fail(resolver, d1, source, d2, a);
                    }
                    if (named == null) {
                        named = new HashMap<>();
                    }
                    ModuleDescriptor prev = named.putIfAbsent(source, d2);
                    if (prev != null) {
                        fail(resolver, d1, source, d2, prev);
                    }
                }
            }
        }
    }

    private static void fail(Object resolver, ModuleDescriptor d1, String source, ModuleDescriptor d2, ModuleDescriptor supplier) {
        try {
            FAIL_TWO_SUPPLIERS.invoke(resolver, d1, source, d2, supplier);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Touches the method handle so a linkage problem surfaces before java.base is patched. */
    public static void selfTest() {
        if (FAIL_TWO_SUPPLIERS == null) {
            throw new IllegalStateException("failTwoSuppliers handle missing");
        }
    }
}

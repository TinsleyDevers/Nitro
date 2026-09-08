package nitro.agent.boot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;

/**
 * Port of JDK 25's {@code java.lang.module.Resolver#makeGraph} to run inside a JDK 21 VM.
 *
 * The only semantic difference to JDK 21 is the guard in the propagation loop: automatic modules
 * already read every selected module, so there is nothing to propagate for them. Without the
 * guard, every automatic module "requires transitive" every other automatic module and the loop
 * degenerates to O(n^3): 1300 mod jars =&gt; ~4.4 billion hash lookups =&gt; minutes of startup.
 *
 * Private members of Resolver are reached through method handles; java.lang.module is opened to
 * this class by the agent via Instrumentation.redefineModule.
 */
public final class FastGraph {
    private static final MethodHandle NAME_TO_REFERENCE;
    private static final MethodHandle PARENTS;
    private static final MethodHandle COMPUTE_IF_ABSENT;
    private static final MethodHandle FIND_IN_PARENT;

    static {
        try {
            Class<?> resolver = Class.forName("java.lang.module.Resolver", false, null);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(resolver, MethodHandles.lookup());
            NAME_TO_REFERENCE = lookup.findGetter(resolver, "nameToReference", Map.class);
            PARENTS = lookup.findGetter(resolver, "parents", List.class);
            COMPUTE_IF_ABSENT = lookup.findVirtual(resolver, "computeIfAbsent",
                MethodType.methodType(ResolvedModule.class, Map.class, String.class, Configuration.class, ModuleReference.class));
            FIND_IN_PARENT = lookup.findVirtual(resolver, "findInParent",
                MethodType.methodType(ResolvedModule.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FastGraph() {}

    /** Forces class initialisation so linkage problems surface before java.base is modified. */
    public static void selfTest() {
        if (NAME_TO_REFERENCE == null) {
            throw new IllegalStateException();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<ResolvedModule, Set<ResolvedModule>> makeGraph(Object resolver, Configuration cf) {
        try {
            Map<String, ModuleReference> nameToReference = (Map<String, ModuleReference>) NAME_TO_REFERENCE.invoke(resolver);
            List<Configuration> parents = (List<Configuration>) PARENTS.invoke(resolver);
            return makeGraph(resolver, cf, nameToReference, parents);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static Map<ResolvedModule, Set<ResolvedModule>> makeGraph(Object resolver,
                                                                      Configuration cf,
                                                                      Map<String, ModuleReference> nameToReference,
                                                                      List<Configuration> parents) throws Throwable {
        int moduleCount = nameToReference.size();

        // the "reads" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ResolvedModule, Set<ResolvedModule>> g1 = HashMap.newHashMap(moduleCount);

        // the "requires transitive" graph, contains requires transitive edges only
        Map<ResolvedModule, Set<ResolvedModule>> g2 = HashMap.newHashMap(moduleCount);

        // need "requires transitive" from the modules in parent configurations
        // as there may be selected modules that have a dependency on modules in
        // the parent configuration.
        if (ModuleLayer.boot() != null) {
            Set<Configuration> distinctParents = new LinkedHashSet<>();
            for (Configuration parent : parents) {
                distinctParents.addAll(allConfigurations(parent));
            }
            for (Configuration c : distinctParents) {
                for (ResolvedModule m1 : c.modules()) {
                    for (ModuleDescriptor.Requires r : m1.reference().descriptor().requires()) {
                        if (!r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                            continue;
                        }
                        Optional<ResolvedModule> m2 = c.findModule(r.name());
                        if (m2.isPresent()) {
                            g2.computeIfAbsent(m1, k -> new HashSet<>()).add(m2.get());
                        }
                    }
                }
            }
        }

        // populate g1 and g2 with the dependences from the selected modules
        Map<String, ResolvedModule> nameToResolved = HashMap.newHashMap(moduleCount);

        for (ModuleReference mref : nameToReference.values()) {
            ModuleDescriptor descriptor = mref.descriptor();
            String name = descriptor.name();

            ResolvedModule m1 = (ResolvedModule) COMPUTE_IF_ABSENT.invoke(resolver, nameToResolved, name, cf, mref);

            Set<ResolvedModule> reads = new HashSet<>();
            Set<ResolvedModule> requiresTransitive = new HashSet<>();

            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String dn = requires.name();

                ResolvedModule m2;
                ModuleReference mref2 = nameToReference.get(dn);
                if (mref2 != null) {
                    // same configuration
                    m2 = (ResolvedModule) COMPUTE_IF_ABSENT.invoke(resolver, nameToResolved, dn, cf, mref2);
                } else {
                    // parent configuration
                    m2 = (ResolvedModule) FIND_IN_PARENT.invoke(resolver, dn);
                    if (m2 == null) {
                        continue;
                    }

                    // m2 is automatic module in parent configuration => m1 reads
                    // all automatic modules that m2 reads.
                    if (m2.reference().descriptor().isAutomatic()) {
                        for (ResolvedModule d : m2.reads()) {
                            if (d.reference().descriptor().isAutomatic()) {
                                reads.add(d);
                            }
                        }
                    }
                }

                // m1 requires m2 => m1 reads m2
                reads.add(m2);

                // m1 requires transitive m2
                if (requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                    requiresTransitive.add(m2);
                }
            }

            // automatic modules read all selected modules and all modules
            // in parent configurations
            if (descriptor.isAutomatic()) {

                // reads all selected modules
                // `requires transitive` all selected automatic modules
                for (ModuleReference mref2 : nameToReference.values()) {
                    ModuleDescriptor descriptor2 = mref2.descriptor();
                    String name2 = descriptor2.name();

                    if (!name.equals(name2)) {
                        ResolvedModule m2 = (ResolvedModule) COMPUTE_IF_ABSENT.invoke(resolver, nameToResolved, name2, cf, mref2);
                        reads.add(m2);
                        if (descriptor2.isAutomatic()) {
                            requiresTransitive.add(m2);
                        }
                    }
                }

                // reads all modules in parent configurations
                // `requires transitive` all automatic modules in parent
                // configurations
                for (Configuration parent : parents) {
                    for (Configuration c : allConfigurations(parent)) {
                        for (ResolvedModule m : c.modules()) {
                            reads.add(m);
                            if (m.reference().descriptor().isAutomatic()) {
                                requiresTransitive.add(m);
                            }
                        }
                    }
                }
            }

            g1.put(m1, reads);
            g2.put(m1, requiresTransitive);
        }

        // Iteratively update g1 until there are no more requires transitive to propagate.
        // JDK 25 fix: automatic modules already read all selected modules, so skip them.
        boolean changed;
        List<ResolvedModule> toAdd = new ArrayList<>();
        do {
            changed = false;
            for (Map.Entry<ResolvedModule, Set<ResolvedModule>> e : g1.entrySet()) {
                ResolvedModule m1 = e.getKey();
                if (m1.reference().descriptor().isAutomatic()) {
                    continue;
                }
                Set<ResolvedModule> m1Reads = e.getValue();
                for (ResolvedModule m2 : m1Reads) {
                    Set<ResolvedModule> m2RequiresTransitive = g2.get(m2);
                    if (m2RequiresTransitive != null) {
                        for (ResolvedModule m3 : m2RequiresTransitive) {
                            if (!m1Reads.contains(m3)) {
                                // m1 reads m2, m2 requires transitive m3
                                // => need to add m1 reads m3
                                toAdd.add(m3);
                            }
                        }
                    }
                }
                if (!toAdd.isEmpty()) {
                    m1Reads.addAll(toAdd);
                    toAdd.clear();
                    changed = true;
                }
            }
        } while (changed);

        return g1;
    }

    /**
     * Equivalent of the package-private {@code Configuration#configurations()}: this configuration
     * followed by all (transitive) parent configurations, each once.
     */
    private static List<Configuration> allConfigurations(Configuration root) {
        List<Configuration> all = new ArrayList<>();
        Set<Configuration> visited = new HashSet<>();
        java.util.ArrayDeque<Configuration> stack = new java.util.ArrayDeque<>();
        visited.add(root);
        stack.push(root);
        while (!stack.isEmpty()) {
            Configuration layer = stack.pop();
            all.add(layer);
            List<Configuration> ps = layer.parents();
            for (int i = ps.size() - 1; i >= 0; i--) {
                Configuration parent = ps.get(i);
                if (visited.add(parent)) {
                    stack.push(parent);
                }
            }
        }
        return all;
    }
}

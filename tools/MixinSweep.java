import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Finds mods in a mods folder that no longer fit each other, without starting the game.
 *
 * After a mass update, the usual way to find a broken pair is to launch, crash, read the report, fix
 * one mod and repeat. This reads every mixin that patches a class owned by another mod jar and checks
 * that the class still declares the method the injector names, and that the method still makes the
 * call an INVOKE injection point targets. Required injections that fail either check stop the game
 * when that class loads, which can be at launch, on world join, or the first time a screen opens.
 *
 * It over-reports on purpose. Mods that ship one mixin per supported version and pick with a plugin
 * show up (the line says when a config has a plugin), and so do targets that another mixin adds at
 * runtime. Read the dates: a finding against a jar that just changed is the one to look at.
 *
 * Run with ASM on the class path:
 *   java -cp asm-9.7.1.jar;asm-tree-9.7.1.jar tools/MixinSweep.java path/to/mods
 */
public class MixinSweep {
    record Loc(String jar, String nested, long jarTime) {}

    static final Map<String, Loc> CLASS_INDEX = new HashMap<>();
    static final Map<String, byte[]> NESTED_BYTES = new HashMap<>();
    static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;");

    public static void main(String[] a) throws Exception {
        Path mods = Path.of(a[0]);
        List<Path> jars;
        try (var s = Files.list(mods)) { jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        for (Path jar : jars) index(jar);
        System.err.println("indexed " + CLASS_INDEX.size() + " classes from " + jars.size() + " jars");

        List<String> crash = new ArrayList<>(), soft = new ArrayList<>();
        for (Path jar : jars) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                for (var e : Collections.list(z.entries())) {
                    String n = e.getName();
                    if (n.contains("/") || !n.endsWith(".json") || !n.contains("mixin")) continue;
                    String json = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                    Matcher pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (!pkg.find()) continue;
                    int defaultRequire = 0;
                    Matcher dr = Pattern.compile("\"defaultRequire\"\\s*:\\s*(\\d+)").matcher(json);
                    if (dr.find()) defaultRequire = Integer.parseInt(dr.group(1));
                    boolean hasPlugin = json.contains("\"plugin\"");
                    for (String list : List.of("mixins", "client", "server")) {
                        Matcher lm = Pattern.compile("\"" + list + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
                        if (!lm.find()) continue;
                        Matcher cm = Pattern.compile("\"([^\"]+)\"").matcher(lm.group(1));
                        while (cm.find()) {
                            String cls = (pkg.group(1) + "." + cm.group(1)).replace('.', '/');
                            ZipEntry ce = z.getEntry(cls + ".class");
                            if (ce == null) continue;
                            check(jar, n, hasPlugin, defaultRequire, z.getInputStream(ce).readAllBytes(), crash, soft);
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("skip " + jar.getFileName() + ": " + ex);
            }
        }
        System.out.println("=== REQUIRED injections whose target is gone (these stop the game when the class loads): " + crash.size());
        crash.forEach(System.out::println);
        System.out.println("=== optional injections whose target is gone (the feature silently does nothing): " + soft.size());
        soft.forEach(System.out::println);
    }

    static void index(Path jar) {
        long t = jar.toFile().lastModified();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var e : Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith(".class")) CLASS_INDEX.putIfAbsent(n.substring(0, n.length() - 6), new Loc(jar.toString(), null, t));
                else if (n.endsWith(".jar") && n.startsWith("META-INF/")) {
                    byte[] bytes = z.getInputStream(e).readAllBytes();
                    String key = jar + "!" + n;
                    boolean any = false;
                    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                        for (ZipEntry ne; (ne = in.getNextEntry()) != null; )
                            if (ne.getName().endsWith(".class")) {
                                CLASS_INDEX.putIfAbsent(ne.getName().substring(0, ne.getName().length() - 6), new Loc(jar.toString(), n, t));
                                any = true;
                            }
                    }
                    if (any) NESTED_BYTES.put(key, bytes);
                }
            }
        } catch (Exception ex) {
            System.err.println("index skip " + jar.getFileName() + ": " + ex);
        }
    }

    static ClassNode load(String cls) throws IOException {
        Loc loc = CLASS_INDEX.get(cls);
        if (loc == null) return null;
        byte[] bytes = null;
        if (loc.nested == null) {
            try (ZipFile z = new ZipFile(loc.jar)) { bytes = z.getInputStream(z.getEntry(cls + ".class")).readAllBytes(); }
        } else {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(NESTED_BYTES.get(loc.jar + "!" + loc.nested)))) {
                for (ZipEntry ne; (ne = in.getNextEntry()) != null; )
                    if (ne.getName().equals(cls + ".class")) { bytes = in.readAllBytes(); break; }
            }
        }
        ClassNode n = new ClassNode();
        new ClassReader(bytes).accept(n, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return n;
    }

    /** Mixin only injects into methods the target class itself declares, so that is all that is checked. */
    static boolean hasMethod(String cls, String name, String desc, int depth) throws IOException {
        ClassNode c = load(cls);
        if (c == null) return true;
        for (MethodNode m : c.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return true;
        return false;
    }

    /** Does any method of that name (and descriptor, when given) in the class contain a call matching the @At target. */
    static boolean callsInside(String cls, String name, String desc, String at) throws IOException {
        ClassNode c = load(cls);
        if (c == null) return true;
        String owner = null, rest = at;
        if (at.startsWith("L") && at.indexOf(';') > 0 && at.indexOf(';') < at.indexOf('(')) { owner = at.substring(1, at.indexOf(';')); rest = at.substring(at.indexOf(';') + 1); }
        String callName = rest.substring(0, rest.indexOf('(')).trim();
        String callDesc = rest.substring(rest.indexOf('(')).trim();
        for (MethodNode m : c.methods) {
            if (!m.name.equals(name) || (desc != null && !m.desc.equals(desc))) continue;
            for (AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode mi && mi.name.equals(callName) && mi.desc.equals(callDesc)) return true;
                if (i instanceof InvokeDynamicInsnNode) { /* lambdas: not a direct call */ }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static void check(Path jar, String config, boolean hasPlugin, int defaultRequire, byte[] bytes, List<String> crash, List<String> soft) throws IOException {
        ClassNode mixin = new ClassNode();
        new ClassReader(bytes).accept(mixin, ClassReader.SKIP_CODE);
        List<String> targets = new ArrayList<>();
        for (AnnotationNode an : all(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || an.values == null) continue;
            for (int i = 0; i < an.values.size(); i += 2) {
                Object v = an.values.get(i + 1);
                if (an.values.get(i).equals("value")) for (Object t : (List<Object>) v) targets.add(((Type) t).getInternalName());
                if (an.values.get(i).equals("targets")) for (Object t : (List<Object>) v) targets.add(((String) t).replace('.', '/'));
            }
        }
        for (String target : targets) {
            if (target.startsWith("net/minecraft/") || target.startsWith("com/mojang/") || target.startsWith("net/neoforged/")) continue;
            Loc loc = CLASS_INDEX.get(target);
            if (loc == null) continue; // the mod this is for is not installed
            if (loc.jar.equals(jar.toString())) continue; // mixin into the mod's own code
            for (MethodNode m : mixin.methods) {
                for (AnnotationNode an : all(m.visibleAnnotations, m.invisibleAnnotations)) {
                    if (!INJECTORS.contains(an.desc) || an.values == null) continue;
                    int require = defaultRequire;
                    List<String> methods = List.of();
                    for (int i = 0; i < an.values.size(); i += 2) {
                        if (an.values.get(i).equals("require")) require = (Integer) an.values.get(i + 1);
                        if (an.values.get(i).equals("method")) methods = (List<String>) an.values.get(i + 1);
                    }
                    List<String> atTargets = new ArrayList<>();
                    for (int i = 0; i < an.values.size(); i += 2) {
                        if (!an.values.get(i).equals("at")) continue;
                        Object v = an.values.get(i + 1);
                        List<Object> ats = v instanceof List<?> l ? (List<Object>) l : List.of(v);
                        for (Object o : ats) {
                            AnnotationNode at = (AnnotationNode) o;
                            String kind = null, tgt = null;
                            if (at.values != null) for (int k = 0; k < at.values.size(); k += 2) {
                                if (at.values.get(k).equals("value")) kind = (String) at.values.get(k + 1);
                                if (at.values.get(k).equals("target")) tgt = (String) at.values.get(k + 1);
                            }
                            if (kind != null && kind.startsWith("INVOKE") && tgt != null && tgt.contains("(") && !tgt.contains("*")) atTargets.add(tgt);
                        }
                    }
                    boolean anyFound = methods.isEmpty();
                    String firstMissing = null;
                    for (String spec : methods) {
                        String s = spec;
                        if (s.startsWith("L") && s.contains(";") && (s.indexOf('(') < 0 || s.indexOf(';') < s.indexOf('('))) s = s.substring(s.indexOf(';') + 1);
                        int p = s.indexOf('(');
                        String name = p < 0 ? s : s.substring(0, p);
                        String desc = p < 0 ? null : s.substring(p);
                        if (name.contains("*") || name.contains("/") || name.isEmpty() || (desc != null && desc.contains("*"))) { anyFound = true; continue; }
                        if (hasMethod(target, name, desc, 0)) {
                            anyFound = true;
                            for (String at : atTargets) {
                                if (!callsInside(target, name, desc, at)) {
                                    String when = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(loc.jarTime));
                                    String line = String.format("  %s (%s)%n      -> %s no longer calls %s%n      in %s%s [changed %s]%s",
                                        jar.getFileName(), mixin.name.substring(mixin.name.lastIndexOf('/') + 1), name, at,
                                        Path.of(loc.jar).getFileName(), loc.nested == null ? "" : " / " + loc.nested.substring(loc.nested.lastIndexOf('/') + 1),
                                        when, hasPlugin ? "  (config has a plugin that may switch this mixin off)" : "");
                                    (require >= 1 ? crash : soft).add(line);
                                }
                            }
                        }
                        else if (firstMissing == null) firstMissing = spec;
                    }
                    if (!anyFound) {
                        String when = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date(loc.jarTime));
                        String line = String.format("  %s (%s)%n      -> %s%n      in %s%s [changed %s]%s",
                            jar.getFileName(), mixin.name.substring(mixin.name.lastIndexOf('/') + 1), firstMissing,
                            Path.of(loc.jar).getFileName(), loc.nested == null ? "" : " / " + loc.nested.substring(loc.nested.lastIndexOf('/') + 1),
                            when, hasPlugin ? "  (config has a plugin that may switch this mixin off)" : "");
                        (require >= 1 ? crash : soft).add(line);
                    }
                }
            }
        }
    }

    @SafeVarargs
    static List<AnnotationNode> all(List<AnnotationNode>... lists) {
        List<AnnotationNode> out = new ArrayList<>();
        for (List<AnnotationNode> l : lists) if (l != null) out.addAll(l);
        return out;
    }
}

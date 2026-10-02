import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Linkage check for a mods folder, without starting the game: every method call and field access
 * from one mod jar into a class owned by another mod jar must resolve to a member that exists,
 * searching superclasses and interfaces (Minecraft and the JDK included, so inherited members count).
 * What does not resolve is a NoSuchMethodError or NoSuchFieldError waiting for that code to run.
 *
 * Companion to MixinSweep: that one finds patches that no longer fit, this one finds plain calls
 * between mods that no longer fit, which is what happens when one of two mods is updated or rolled
 * back without the other.
 *
 * It over-reports. A library bundled inside several mods shows up as one mod "using" another, and
 * multi-loader jars carry code for other loaders that never runs. Give it name filters to look at one
 * group of mods, and use --without / --with to try a swap before making it:
 *
 *   java -cp asm-9.7.1.jar tools/LinkSweep.java path/to/mods --platform=patched-client.jar;neoforge-universal.jar  *        --without=somemod-1.0 --with=path/to/somemod-1.1.jar somemod
 */
public class LinkSweep {
    static final class Info {
        String jar; String superName; String[] interfaces;
        final Set<String> members = new HashSet<>(); // "m name desc" / "f name"
    }

    static final String PLATFORM = "<minecraft>";
    static final Map<String, Info> CLASSES = new HashMap<>(400_000);

    public static void main(String[] a) throws Exception {
        Path mods = Path.of(a[0]);
        List<String> filters = new ArrayList<>();
        List<Path> platform = new ArrayList<>();
        List<String> without = new ArrayList<>();
        List<Path> with = new ArrayList<>();
        for (String arg : Arrays.asList(a).subList(1, a.length)) {
            if (arg.startsWith("--platform=")) for (String p : arg.substring(11).split(";")) platform.add(Path.of(p));
            else if (arg.startsWith("--without=")) without.add(arg.substring(10));
            else if (arg.startsWith("--with=")) with.add(Path.of(arg.substring(7)));
            else filters.add(arg);
        }
        List<Path> jars;
        try (var s = Files.list(mods)) { jars = new ArrayList<>(s.filter(p -> p.toString().endsWith(".jar")).sorted().toList()); }

        // Simulate a swap without touching the folder: drop some jars, add others.
        jars.removeIf(p -> without.stream().anyMatch(p.getFileName().toString()::contains));
        jars.addAll(with);

        // Pass 1: what every class declares. Minecraft and the loader go in too, marked as platform, so
        // a method inherited from a Minecraft class resolves instead of being guessed at.
        List<Path> all = new ArrayList<>(platform);
        all.addAll(jars);
        for (Path jar : all) forEachClass(jar, (name, bytes) -> {
            Info info = new Info();
            info.jar = platform.contains(jar) ? PLATFORM : jar.getFileName().toString();
            try {
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public void visit(int v, int acc, String n, String sig, String sup, String[] ifs) { info.superName = sup; info.interfaces = ifs; }
                    @Override public FieldVisitor visitField(int acc, String n, String d, String s, Object val) { info.members.add("f " + n); return null; }
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) { info.members.add("m " + n + " " + d); return null; }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (Throwable t) { return; }
            CLASSES.putIfAbsent(name, info);
        });
        System.err.println("indexed " + CLASSES.size() + " classes from " + jars.size() + " jars");

        // Pass 2: every reference into another jar.
        Map<String, List<String>> broken = new TreeMap<>();
        for (Path jar : jars) {
            String from = jar.getFileName().toString();
            forEachClass(jar, (name, bytes) -> {
                try {
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int acc, String mn, String md, String s, String[] ex) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) { ref(owner, "m " + n + " " + d); }
                                @Override public void visitFieldInsn(int op, String owner, String n, String d) { ref(owner, "f " + n); }
                                void ref(String owner, String member) {
                                    if (owner.startsWith("[")) return;
                                    Info target = CLASSES.get(owner);
                                    if (target == null || target.jar.equals(from) || target.jar == PLATFORM) return;
                                    if (!filters.isEmpty() && filters.stream().noneMatch(target.jar::contains) && filters.stream().noneMatch(from::contains)) return;
                                    if (resolves(owner, member, new HashSet<>())) return;
                                    broken.computeIfAbsent(from + "  ->  " + target.jar, k -> new ArrayList<>())
                                        .add(name.substring(name.lastIndexOf('/') + 1) + "." + mn + " uses " + owner.substring(owner.lastIndexOf('/') + 1) + "." + member.substring(2));
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable ignored) { }
            });
        }
        int total = 0;
        for (var e : broken.entrySet()) {
            List<String> uniq = new ArrayList<>(new LinkedHashSet<>(e.getValue()));
            total += uniq.size();
            System.out.println(e.getKey() + "   (" + uniq.size() + ")");
            uniq.stream().limit(3).forEach(x -> System.out.println("      " + x));
        }
        System.out.println("TOTAL unresolved cross-mod references: " + total + " in " + broken.size() + " jar pairs");
    }

    /** True when the member is found on the class or anything it extends; leaving the mods folder counts as found. */
    static boolean resolves(String cls, String member, Set<String> seen) {
        if (!seen.add(cls)) return false;
        Info i = CLASSES.get(cls);
        if (i == null) return cls.startsWith("java/") || cls.startsWith("javax/") ? jdkHas(cls, member) : true; // a library we cannot see: assume fine
        if (i.members.contains(member)) return true;
        if (member.startsWith("m ") && (member.startsWith("m <init> ") )) return false;
        if (i.superName != null && resolves(i.superName, member, seen)) return true;
        if (i.interfaces != null) for (String s : i.interfaces) if (resolves(s, member, seen)) return true;
        return false;
    }

    static final Map<String, Set<String>> JDK = new HashMap<>();

    /** JDK classes are resolved for real, through reflection, including everything they inherit. */
    static boolean jdkHas(String cls, String member) {
        Set<String> members = JDK.computeIfAbsent(cls, k -> {
            Set<String> out = new HashSet<>();
            try {
                Class<?> c = Class.forName(k.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                Deque<Class<?>> todo = new ArrayDeque<>(List.of(c));
                while (!todo.isEmpty()) {
                    Class<?> x = todo.pop();
                    for (var m : x.getDeclaredMethods()) out.add("m " + m.getName() + " " + Type.getMethodDescriptor(m));
                    for (var f : x.getDeclaredFields()) out.add("f " + f.getName());
                    if (x.getSuperclass() != null) todo.push(x.getSuperclass());
                    todo.addAll(Arrays.asList(x.getInterfaces()));
                }
            } catch (Throwable t) {
                return null;
            }
            return out;
        });
        return members == null || members.contains(member);
    }

    interface Sink { void accept(String name, byte[] bytes); }

    static void forEachClass(Path jar, Sink sink) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var e : Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) sink.accept(n.substring(0, n.length() - 6), z.getInputStream(e).readAllBytes());
                else if (n.endsWith(".jar") && n.startsWith("META-INF/")) {
                    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(z.getInputStream(e).readAllBytes()))) {
                        for (ZipEntry ne; (ne = in.getNextEntry()) != null; )
                            if (ne.getName().endsWith(".class") && !ne.getName().startsWith("META-INF/")) sink.accept(ne.getName().substring(0, ne.getName().length() - 6), in.readAllBytes());
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("skip " + jar.getFileName() + ": " + ex);
        }
    }
}

package nitro.agent.boot;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/**
 * Lives on the bootstrap class path (see NitroAgent). Rewrites the body of
 * {@code java.lang.module.Resolver#makeGraph(Configuration)} so it delegates to
 * {@link FastGraph#makeGraph(Object, java.lang.module.Configuration)}, which implements the
 * JDK 25 algorithm.
 *
 * Retransformation of a java.base class is allowed as long as the class "shape" (fields, methods,
 * signatures) is untouched, which is why the replacement body is a two-instruction delegation and
 * the real algorithm lives in a separate bootstrap class.
 */
public final class ResolverPatch {
    private static final String RESOLVER = "java/lang/module/Resolver";
    private static final String MAKE_GRAPH_DESC = "(Ljava/lang/module/Configuration;)Ljava/util/Map;";
    private static final String CHECK_EXPORTS_DESC = "(Ljava/util/Map;)V";
    private static final int ASM_API = Opcodes.ASM9;

    private ResolverPatch() {}

    public static boolean apply(Instrumentation inst) throws Exception {
        if (!inst.isRetransformClassesSupported()) {
            throw new IllegalStateException("JVM does not support class retransformation");
        }
        Class<?> resolver = Class.forName("java.lang.module.Resolver", false, null);
        if (!inst.isModifiableClass(resolver)) {
            throw new IllegalStateException("java.lang.module.Resolver is not modifiable");
        }
        // Make sure FastGraph links fine before we touch java.base.
        FastGraph.selfTest();
        FastExports.selfTest();

        AtomicBoolean applied = new AtomicBoolean(false);
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!RESOLVER.equals(className)) {
                    return null;
                }
                byte[] out = patch(classfileBuffer);
                applied.set(out != null);
                return out;
            }
        };
        inst.addTransformer(transformer, true);
        try {
            inst.retransformClasses(resolver);
        } finally {
            inst.removeTransformer(transformer);
        }
        return applied.get();
    }

    static byte[] patch(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("makeGraph".equals(name) && MAKE_GRAPH_DESC.equals(descriptor)) {
                    found.set(true);
                    // return FastGraph.makeGraph(this, cf);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "nitro/agent/boot/FastGraph", "makeGraph",
                        "(Ljava/lang/Object;Ljava/lang/module/Configuration;)Ljava/util/Map;", false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    // Discard the original instructions.
                    return new MethodVisitor(ASM_API) {};
                }
                if ("checkExportSuppliers".equals(name) && CHECK_EXPORTS_DESC.equals(descriptor)) {
                    // FastExports.checkExportSuppliers(this, graph);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "nitro/agent/boot/FastExports", "checkExportSuppliers",
                        "(Ljava/lang/Object;Ljava/util/Map;)V", false);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    return new MethodVisitor(ASM_API) {};
                }
                return mv;
            }
        };
        reader.accept(visitor, 0);
        return found.get() ? writer.toByteArray() : null;
    }
}

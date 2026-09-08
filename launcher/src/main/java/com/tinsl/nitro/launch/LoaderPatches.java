package com.tinsl.nitro.launch;

import nitro.agent.boot.NitroHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Patches for the mod-loading toolchain itself (not the game, not mods), applied with the agent's
 * {@link Instrumentation}. All patches keep method signatures and class shape unchanged, which is
 * what retransformation requires, and delegate to {@link NitroHooks} on the bootstrap class path.
 *
 * <ol>
 *   <li><b>Mixin</b> {@code Bytecode#getOpcodeName(int, String, int)}: Mixin 0.8.7 describes every
 *       instruction of every applied mixin method for its activity stack, and that description
 *       looks the opcode name up with reflection over {@code Opcodes}' fields each time (~5
 *       CPU-seconds on this pack). Replaced with a cached table.</li>
 *   <li><b>Mixin</b> {@code MixinProcessor#applyMixins}: every class load asks all 600+ configs
 *       whether they target the class. The two loops now iterate only the configs an index says
 *       can match (see {@link MixinConfigIndexer}); {@code MixinConfig#prepare} reports changes.</li>
 *   <li><b>Secure Jar Handler</b> {@code ModuleClassLoader#findResourceList(String)}: resources
 *       outside any known package (native-library folders, META-INF, root files) are searched by
 *       probing every module's jar. The candidate set is narrowed with a directory index, see
 *       {@link ResourceIndexFilter}.</li>
 * </ol>
 */
final class LoaderPatches {
    private static final Logger LOGGER = LogManager.getLogger("Nitro");

    private static final String MIXIN_BYTECODE = "org/spongepowered/asm/util/Bytecode";
    private static final String MIXIN_PROCESSOR = "org/spongepowered/asm/mixin/transformer/MixinProcessor";
    private static final String MIXIN_CONFIG = "org/spongepowered/asm/mixin/transformer/MixinConfig";
    private static final String SJH_MCL = "cpw/mods/cl/ModuleClassLoader";
    private static final String CLASS_TRANSFORMER = "cpw/mods/modlauncher/ClassTransformer";
    private static final String AUDIT_TRAIL = "cpw/mods/modlauncher/TransformerAuditTrail";
    private static final String HOOKS = "nitro/agent/boot/NitroHooks";

    private LoaderPatches() {}

    static void apply(Instrumentation inst, java.nio.file.Path gameDir, boolean classCache) {
        Module bootUnnamed = NitroHooks.class.getModule();
        Module self = LoaderPatches.class.getModule();

        // --- ModLauncher class transform cache -----------------------------------------------------
        if (classCache) {
            try {
                Class<?> ct = Class.forName(CLASS_TRANSFORMER.replace('/', '.'), false, cpw.mods.modlauncher.api.ITransformationService.class.getClassLoader());
                inst.redefineModule(ct.getModule(), Set.of(bootUnnamed), Map.of(), Map.of(), Set.of(), Map.of());
                ClassTransformCache cache = ClassTransformCache.install(gameDir);
                if (cache != null) {
                    boolean ok = retransform(inst, ct, CLASS_TRANSFORMER, LoaderPatches::patchClassTransformer);
                    if (ok) {
                        NitroHooks.CLASS_CACHE = cache;
                        LOGGER.info("Nitro: patched ModLauncher ClassTransformer.transform to serve unchanged classes from the on-disk transform cache");
                    } else {
                        LOGGER.warn("Nitro: ClassTransformer.transform did not match the expected shape; class cache skipped");
                    }
                }
            } catch (Throwable t) {
                NitroHooks.CLASS_CACHE = null;
                LOGGER.warn("Nitro: failed to install the class transform cache (harmless, startup just stays slower)", t);
            }
        }

        // --- Secure Jar Handler -------------------------------------------------------------------
        try {
            Class<?> mcl = cpw.mods.cl.ModuleClassLoader.class;
            Module sjh = mcl.getModule();
            // patched code calls NitroHooks; our index code reflects into the union file system
            inst.redefineModule(sjh, Set.of(bootUnnamed), Map.of(), Map.of("cpw.mods.niofs.union", Set.of(self)), Set.of(), Map.of());
            NitroHooks.RESOURCE_FILTER = new ResourceIndexFilter();
            boolean ok = retransform(inst, mcl, SJH_MCL, LoaderPatches::patchModuleClassLoader);
            if (ok) {
                LOGGER.info("Nitro: patched SecureJarHandler ModuleClassLoader.findResourceList to use a jar directory index "
                    + "for lookups outside known packages (native libraries, META-INF, ...)");
            } else {
                NitroHooks.RESOURCE_FILTER = null;
                LOGGER.warn("Nitro: ModuleClassLoader.findResourceList did not match the expected shape; resource index patch skipped");
            }
        } catch (Throwable t) {
            NitroHooks.RESOURCE_FILTER = null;
            LOGGER.warn("Nitro: failed to patch SecureJarHandler resource lookup (harmless, startup just stays slower)", t);
        }

        // --- Mixin --------------------------------------------------------------------------------
        Optional<Module> mixin = findModuleWithPackage(self, "org.spongepowered.asm.util");
        if (mixin.isEmpty()) {
            LOGGER.info("Nitro: Mixin module not found in any parent layer; Mixin patches skipped");
            return;
        }
        Module m = mixin.get();
        ClassLoader loader = m.getClassLoader();
        try {
            inst.redefineModule(m, Set.of(bootUnnamed), Map.of(),
                Map.of("org.spongepowered.asm.mixin.transformer", Set.of(self)), Set.of(), Map.of());
        } catch (Throwable t) {
            LOGGER.warn("Nitro: could not adjust the Mixin module's reads/opens; Mixin patches skipped", t);
            return;
        }
        try {
            Class<?> bytecode = Class.forName("org.spongepowered.asm.util.Bytecode", false, loader);
            boolean ok = retransform(inst, bytecode, MIXIN_BYTECODE, LoaderPatches::patchMixinBytecode);
            if (ok) {
                LOGGER.info("Nitro: patched Mixin Bytecode.getOpcodeName (reflective Opcodes field scan per instruction) with a cached table");
            } else {
                LOGGER.info("Nitro: Mixin Bytecode.getOpcodeName(int,String,int) not found (different Mixin version?); patch skipped");
            }
        } catch (Throwable t) {
            LOGGER.warn("Nitro: failed to patch Mixin opcode lookup (harmless)", t);
        }
        try {
            Class<?> configClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig", false, loader);
            Class<?> processorClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinProcessor", false, loader);
            MixinConfigIndexer indexer = new MixinConfigIndexer(configClass);
            boolean prepareOk = retransform(inst, configClass, MIXIN_CONFIG, LoaderPatches::patchMixinConfig);
            boolean scanOk = prepareOk && retransform(inst, processorClass, MIXIN_PROCESSOR, LoaderPatches::patchMixinProcessor);
            if (scanOk) {
                NitroHooks.CONFIG_INDEXER = indexer;
                LOGGER.info("Nitro: patched Mixin MixinProcessor.applyMixins to consult a target index instead of asking every config on every class load");
            } else {
                LOGGER.info("Nitro: Mixin config-scan patch skipped (prepare hook: {}, scan: {})", prepareOk, scanOk);
            }
        } catch (Throwable t) {
            NitroHooks.CONFIG_INDEXER = null;
            LOGGER.warn("Nitro: failed to patch Mixin config scan (harmless)", t);
        }
    }

    private interface Patcher {
        byte[] patch(byte[] original);
    }

    /** Registers a one-shot transformer, retransforms the class, removes the transformer again. */
    private static boolean retransform(Instrumentation inst, Class<?> target, String internalName, Patcher patcher) throws Exception {
        if (!inst.isModifiableClass(target)) {
            LOGGER.warn("Nitro: {} is not modifiable", internalName);
            return false;
        }
        AtomicBoolean applied = new AtomicBoolean(false);
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!internalName.equals(className)) {
                    return null;
                }
                try {
                    byte[] out = patcher.patch(classfileBuffer);
                    applied.set(out != null);
                    return out;
                } catch (Throwable t) {
                    LOGGER.warn("Nitro: patch for {} threw, leaving class untouched", className, t);
                    return null;
                }
            }
        };
        inst.addTransformer(transformer, true);
        try {
            inst.retransformClasses(target);
        } finally {
            // No transformer stays registered: with none left the JVM disables its class-file load
            // hook, so the other ~300k class loads of this launch pay nothing.
            inst.removeTransformer(transformer);
        }
        return applied.get();
    }

    private static Optional<Module> findModuleWithPackage(Module from, String pkg) {
        ModuleLayer layer = from.getLayer();
        List<ModuleLayer> queue = new ArrayList<>();
        if (layer != null) {
            queue.add(layer);
        }
        for (int i = 0; i < queue.size(); i++) {
            ModuleLayer l = queue.get(i);
            for (Module m : l.modules()) {
                if (m.getPackages().contains(pkg)) {
                    return Optional.of(m);
                }
            }
            for (ModuleLayer p : l.parents()) {
                if (!queue.contains(p)) {
                    queue.add(p);
                }
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------------------------------
    // Mixin: replace the body of getOpcodeName(int, String, int) with
    //     return NitroHooks.opcodeName(opcode, start, min, Opcodes.class);
    // -------------------------------------------------------------------------------------------------
    static byte[] patchMixinBytecode(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("getOpcodeName".equals(name) && "(ILjava/lang/String;I)Ljava/lang/String;".equals(descriptor)) {
                    found.set(true);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ILOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitVarInsn(Opcodes.ILOAD, 2);
                    mv.visitLdcInsn(Type.getObjectType("org/objectweb/asm/Opcodes"));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "opcodeName",
                        "(ILjava/lang/String;ILjava/lang/Class;)Ljava/lang/String;", false);
                    mv.visitInsn(Opcodes.ARETURN);
                    mv.visitMaxs(4, 3);
                    mv.visitEnd();
                    return new MethodVisitor(Opcodes.ASM9) {}; // drop the original instructions
                }
                return mv;
            }
        };
        reader.accept(visitor, 0);
        return found.get() ? writer.toByteArray() : null;
    }

    // -------------------------------------------------------------------------------------------------
    // Mixin: in MixinProcessor.applyMixins(MixinEnvironment, String name, ClassNode), replace every
    //     this.configs            (as the source of a for-each loop)
    // with
    //     NitroHooks.mixinConfigsFor(this.configs, name)
    // -------------------------------------------------------------------------------------------------
    static byte[] patchMixinProcessor(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"applyMixins".equals(name)
                    || !"(Lorg/spongepowered/asm/mixin/MixinEnvironment;Ljava/lang/String;Lorg/objectweb/asm/tree/ClassNode;)Z".equals(descriptor)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean pendingConfigs;

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String desc) {
                        super.visitFieldInsn(opcode, owner, fname, desc);
                        pendingConfigs = opcode == Opcodes.GETFIELD && "configs".equals(fname) && "Ljava/util/List;".equals(desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String desc, boolean itf) {
                        if (pendingConfigs && opcode == Opcodes.INVOKEINTERFACE && "java/util/List".equals(owner) && "iterator".equals(mname)) {
                            // stack: [List] -> [List, name] -> [List]
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "mixinConfigsFor",
                                "(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;", false);
                            found.set(true);
                        }
                        pendingConfigs = false;
                        super.visitMethodInsn(opcode, owner, mname, desc, itf);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        pendingConfigs = false;
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        pendingConfigs = false;
                        super.visitVarInsn(opcode, var);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return found.get() ? writer.toByteArray() : null;
    }

    // -------------------------------------------------------------------------------------------------
    // Mixin: MixinConfig.prepare() notifies the index before every return.
    // -------------------------------------------------------------------------------------------------
    static byte[] patchMixinConfig(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"prepare".equals(name) || !descriptor.startsWith("(")) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "mixinConfigsChanged", "()V", false);
                            found.set(true);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return found.get() ? writer.toByteArray() : null;
    }

    // -------------------------------------------------------------------------------------------------
    // ModLauncher: wrap transform(byte[] input, String className, String reason) as
    //     byte[] cached = NitroHooks.classCacheLookup(this, input, className, reason);
    //     if (cached != null) { this.auditTrail.addReason(className, reason); return cached; }
    //     ... original body, every return value passed through NitroHooks.classCacheStore(...) ...
    // The method keeps its shape (no new locals), so the only new stack-map frame is the one at the
    // join point after the early return, which equals the entry frame plus the null on the stack.
    // -------------------------------------------------------------------------------------------------
    static byte[] patchClassTransformer(byte[] original) {
        ClassReader reader = new ClassReader(original);
        AtomicBoolean hasAuditTrail = new AtomicBoolean(false);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if ("auditTrail".equals(name) && ("L" + AUDIT_TRAIL + ";").equals(descriptor)) {
                    hasAuditTrail.set(true);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"transform".equals(name) || !"([BLjava/lang/String;Ljava/lang/String;)[B".equals(descriptor)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitVarInsn(Opcodes.ALOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "classCacheLookup",
                            "(Ljava/lang/Object;[BLjava/lang/String;Ljava/lang/String;)[B", false);
                        super.visitInsn(Opcodes.DUP);
                        org.objectweb.asm.Label miss = new org.objectweb.asm.Label();
                        super.visitJumpInsn(Opcodes.IFNULL, miss);
                        if (hasAuditTrail.get()) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, CLASS_TRANSFORMER, "auditTrail", "L" + AUDIT_TRAIL + ";");
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 3);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, AUDIT_TRAIL, "addReason", "(Ljava/lang/String;Ljava/lang/String;)V", false);
                        }
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(miss);
                        super.visitFrame(Opcodes.F_NEW, 4, new Object[] {CLASS_TRANSFORMER, "[B", "java/lang/String", "java/lang/String"}, 1, new Object[] {"[B"});
                        super.visitInsn(Opcodes.POP);
                        found.set(true);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 3);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "classCacheStore",
                                "([BLjava/lang/Object;[BLjava/lang/String;Ljava/lang/String;)[B", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return found.get() ? writer.toByteArray() : null;
    }

    // -------------------------------------------------------------------------------------------------
    // SJH: inside findResourceList(String), replace
    //     this.resolvedRoots.values()
    // with
    //     NitroHooks.resourceCandidates(this.resolvedRoots, this, name)
    // The fast path (known package) and the rest of the stream pipeline stay untouched.
    // -------------------------------------------------------------------------------------------------
    static byte[] patchModuleClassLoader(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        AtomicBoolean found = new AtomicBoolean(false);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"findResourceList".equals(name) || !"(Ljava/lang/String;)Ljava/util/List;".equals(descriptor)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    private boolean lastWasResolvedRoots;

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String desc) {
                        lastWasResolvedRoots = opcode == Opcodes.GETFIELD && "resolvedRoots".equals(fname);
                        super.visitFieldInsn(opcode, owner, fname, desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String desc, boolean itf) {
                        if (lastWasResolvedRoots && opcode == Opcodes.INVOKEINTERFACE && "java/util/Map".equals(owner)
                            && "values".equals(mname) && "()Ljava/util/Collection;".equals(desc)) {
                            // stack: [Map]  ->  [Map, this, name]  ->  [Collection]
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "resourceCandidates",
                                "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/String;)Ljava/util/Collection;", false);
                            found.set(true);
                        } else {
                            super.visitMethodInsn(opcode, owner, mname, desc, itf);
                        }
                        lastWasResolvedRoots = false;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        lastWasResolvedRoots = false;
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        lastWasResolvedRoots = false;
                        super.visitVarInsn(opcode, var);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return found.get() ? writer.toByteArray() : null;
    }
}

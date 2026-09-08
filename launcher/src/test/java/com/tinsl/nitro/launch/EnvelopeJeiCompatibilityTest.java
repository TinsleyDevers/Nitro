package com.tinsl.nitro.launch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class EnvelopeJeiCompatibilityTest {
    private ClassNode read(String jar, String name) throws Exception {
        Path path = Path.of(System.getProperty("nitro.packDir"), "mods", jar);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(path), "Supply -PpackDir for real installed-jar tests");
        try (ZipFile zip = new ZipFile(path.toFile()); var in = zip.getInputStream(zip.getEntry(name + ".class"))) {
            ClassNode node = new ClassNode(); new ClassReader(in).accept(node, 0); return node;
        }
    }
    @Test void adaptsInstalledEnvelopeAndPreservesBytecodeValidity() throws Exception {
        ClassNode envelope = read("envelope-neoforge-1.21.1-0.7.5.jar", "io/github/mortuusars/envelope/mixin/jei/BasicRecipeTransferHandlerMixin");
        assertTrue(EnvelopeJeiCompatibility.patch(envelope));
        assertFalse(EnvelopeJeiCompatibility.patch(envelope), "Must be idempotent");
        MethodNode callback = envelope.methods.stream().filter(m -> m.name.equals("onTransferRecipe")).findFirst().orElseThrow();
        assertTrue(callback.desc.contains(EnvelopeJeiCompatibility.CONTEXT));
        new Analyzer<>(new BasicVerifier()).analyze(envelope.name, callback);
        ClassNode jei = read("jei-1.21.1-neoforge-19.53.0.425.jar", "mezz/jei/library/transfer/BasicRecipeTransferHandler");
        MethodNode internal = jei.methods.stream().filter(m -> m.name.equals("transferRecipeInternal")).findFirst().orElseThrow();
        String targetArgs = internal.desc.substring(0, internal.desc.indexOf(')'));
        assertEquals(targetArgs + EnvelopeJeiCompatibility.CALLBACK + ")V", callback.desc);
        int packetPaths = 0;
        for (var insn : internal.instructions) if (insn instanceof MethodInsnNode call) {
            String target = "L" + call.owner + ";" + call.name + call.desc;
            if (target.equals(EnvelopeJeiCompatibility.PACKET) || target.equals(EnvelopeJeiCompatibility.RESULT)) packetPaths++;
        }
        assertEquals(3, packetPaths, "Both legacy packet variants and result-aware transfer must be covered");
    }
    @Test void unknownShapeIsNotChanged() { assertFalse(EnvelopeJeiCompatibility.patch(new ClassNode())); }
}

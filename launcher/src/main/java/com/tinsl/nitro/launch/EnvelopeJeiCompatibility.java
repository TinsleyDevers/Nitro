package com.tinsl.nitro.launch;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import cpw.mods.modlauncher.api.TargetType;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.tree.*;
import java.util.List;
import java.util.Set;

/** Retarget Envelope 0.7.5's mail-address callback after JEI extracted transferRecipeInternal.
 * Operates on mixin bytes before Mixin prepares them; does not edit either installed mod jar.
 */
public final class EnvelopeJeiCompatibility implements ITransformer<ClassNode> {
    static final String CONTEXT = "Lmezz/jei/api/recipe/transfer/IRecipeTransferContext;";
    static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    static final String ARGS = "(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ";
    static final String PACKET = "Lmezz/jei/common/network/IConnectionToServer;sendPacketToServer(Lmezz/jei/common/network/packets/PlayToServerPacket;)V";
    static final String RESULT = "Lmezz/jei/library/transfer/BasicRecipeTransferHandler;sendTransferWithResult(Ljava/util/List;Ljava/util/List;Ljava/util/List;ZZZ" + CONTEXT + ")V";

    @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        if (patch(input)) LogManager.getLogger("Nitro").info("Nitro: adapted Envelope JEI mail transfer callback to the moved JEI transfer handler (including result packets)");
        return input;
    }

    static boolean patch(ClassNode input) {
        for (MethodNode method : input.methods) {
            if (!method.name.equals("onTransferRecipe") || !method.desc.equals(ARGS + CALLBACK + ")V")) continue;
            if (method.visibleAnnotations == null) continue;
            for (AnnotationNode inject : method.visibleAnnotations) {
                if (!inject.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                int atIndex = inject.values.indexOf("at");
                int methodIndex = inject.values.indexOf("method");
                if (atIndex < 0 || methodIndex < 0) continue;
                if (!(inject.values.get(atIndex + 1) instanceof List<?> ats) || ats.size() != 1
                    || !(ats.getFirst() instanceof AnnotationNode at) || !at.values.contains(PACKET)) continue;
                inject.values.set(methodIndex + 1, List.of("transferRecipeInternal" + ARGS + CONTEXT + ")Lmezz/jei/api/recipe/transfer/IRecipeTransferError;"));
                AnnotationNode resultAt = new AnnotationNode(at.desc);
                resultAt.values = List.of("value", "INVOKE", "target", RESULT);
                inject.values.set(atIndex + 1, List.of(at, resultAt));
                method.desc = ARGS + CONTEXT + CALLBACK + ")V";
                method.signature = null;
                // New argument at slot 7: shift callback and all following locals.
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof VarInsnNode var && var.var >= 7) var.var++;
                    if (insn instanceof IincInsnNode inc && inc.var >= 7) inc.var++;
                    if (insn instanceof FrameNode) method.instructions.remove(insn);
                }
                if (method.localVariables != null) method.localVariables.forEach(v -> { if (v.index >= 7) v.index++; });
                method.visibleLocalVariableAnnotations = null;
                method.invisibleLocalVariableAnnotations = null;
                if (method.parameters != null) method.parameters.add(6, new ParameterNode("nitroContext", 0));
                method.maxLocals++;
                return true;
            }
        }
        return false;
    }

    @Override public TransformerVoteResult castVote(ITransformerVotingContext context) { return TransformerVoteResult.YES; }
    @Override public Set<Target<ClassNode>> targets() { return Set.of(Target.targetClass("io.github.mortuusars.envelope.mixin.jei.BasicRecipeTransferHandlerMixin")); }
    @Override public TargetType<ClassNode> getTargetType() { return TargetType.CLASS; }
}

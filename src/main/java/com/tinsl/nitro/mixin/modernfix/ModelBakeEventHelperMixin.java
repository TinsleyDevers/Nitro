package com.tinsl.nitro.mixin.modernfix;

import com.tinsl.nitro.Nitro;
import com.tinsl.nitro.common.NitroSettings;
import com.tinsl.nitro.compat.ModTargets;
import net.neoforged.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;

/**
 * ModernFix's "dynamic resources" load models lazily, but any mod whose ModifyBakingResult
 * handler iterates the whole model registry forces every model in the game to load and bake on a
 * single thread. ModernFix restricts a handful of known mods to "their own models and their
 * dependencies' models" through a hard-coded table; everything else sees the universe.
 *
 * Profiling this pack showed DragonLib's handler alone accounted for ~40 s of the reload by doing
 * exactly that (it re-dispatches every model to its own event). This mixin lets the Nitro config
 * extend ModernFix's table with additional mod ids that get SELF_AND_DEPS visibility.
 */
@Mixin(targets = "org.embeddedt.modernfix.neoforge.dynresources.ModelBakeEventHelper", remap = false)
public abstract class ModelBakeEventHelperMixin {
    @Redirect(
        method = "wrapRegistry",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private Object nitro$visibility(Map<Object, Object> table, Object modId, Object everything) {
        Object configured = table.get(modId);
        if (configured != null) {
            return configured;
        }
        NitroSettings settings = NitroSettings.load(FMLPaths.GAMEDIR.get());
        Set<String> restricted = settings.modelVisibility;
        if (modId instanceof String id && everything instanceof Enum<?> e
            && (restricted.contains(id) || nitro$knownRestrictedHandler(id, settings.compatFixes))) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object selfAndDeps = Enum.valueOf((Class) e.getDeclaringClass(), "SELF_AND_DEPS");
                Nitro.LOGGER.info("Nitro: restricting model-bake event registry for '{}' to its own models and its dependencies' models", id);
                return selfAndDeps;
            } catch (IllegalArgumentException ignored) {
                // enum constant renamed in this ModernFix version; fall through
            }
        }
        return everything;
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean nitro$knownRestrictedHandler(String modId, boolean enabled) {
        // These versions only wrap their own models, plus Simulated docking connectors for Create Thrusters.
        return switch (modId) {
            case "createthrusters" -> ModTargets.check("thrusterModels", enabled).active();
            case "industrialforegoing" -> ModTargets.check("industrialModels", enabled).active();
            case "trmt" -> ModTargets.check("trmtModels", enabled).active();
            default -> false;
        };
    }
}

package com.tinsl.nitro.mixin.compat;

import com.tinsl.nitro.Nitro;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Create Ore Excavation registers a KubeJS type wrapper for Create's {@code ProcessingOutput} unless
 * its {@code kubeJSCreate} flag says KubeJS Create is installed (which registers the same wrapper).
 * The flag is assigned in Create Ore Excavation's mod constructor, but KubeJS runs every plugin's
 * {@code registerTypeWrappers} from its own constructor, and NeoForge constructs mods in parallel.
 * When KubeJS gets there first the flag is still false, both wrappers are registered and KubeJS
 * fails to load ("Wrapper for class ... ProcessingOutput already exists!"). The mod list is final
 * long before either constructor runs, so the flag is derived from it right before it is read.
 */
@Mixin(targets = "com.tom.createores.kubejs.KubeJSExcavation", remap = false)
public abstract class CreateOreExcavationKubePluginMixin {
    @Inject(method = "registerTypeWrappers", at = @At("HEAD"), remap = false)
    private void nitro$settleKubeJsCreateFlag(CallbackInfo ci) {
        try {
            Class<?> main = Class.forName("com.tom.createores.CreateOreExcavation", false, CreateOreExcavationKubePluginMixin.class.getClassLoader());
            Field flag = main.getField("kubeJSCreate");
            boolean present = ModList.get().isLoaded("kubejs_create");
            if (flag.getBoolean(null) != present) {
                flag.setBoolean(null, present);
                Nitro.LOGGER.info("Nitro: Create Ore Excavation's KubeJS plugin ran before its mod constructor; kubeJSCreate set to {} from the mod list", present);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Nitro.LOGGER.warn("Nitro: could not settle Create Ore Excavation's kubeJSCreate flag: {}", e.toString());
        }
    }
}

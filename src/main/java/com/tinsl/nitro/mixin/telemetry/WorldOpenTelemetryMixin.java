package com.tinsl.nitro.mixin.telemetry;
import com.tinsl.nitro.client.StartupTelemetry;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenTelemetryMixin {
 @Inject(method = "openWorld", at = @At("HEAD"))
 private void nitro$begin(CallbackInfo ci) { StartupTelemetry.beginWorldLoad(); }
}

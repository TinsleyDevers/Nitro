package com.tinsl.nitro.mixin.telemetry;
import com.tinsl.nitro.client.StartupTelemetry;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ConnectScreen.class)
public abstract class ConnectTelemetryMixin {
 @Inject(method = "startConnecting", at = @At("HEAD"))
 private static void nitro$begin(CallbackInfo ci) { StartupTelemetry.beginWorldLoad(); }
}

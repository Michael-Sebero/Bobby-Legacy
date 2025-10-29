package com.michaelsebero.bobby.mixin;

import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameSettings.Options.class)
public class GameSettings$OptionsMixin {
    
    // Override getValueMax to always return 1816 for RENDER_DISTANCE
    @Inject(method = "getValueMax", at = @At("HEAD"), cancellable = true)
    private void bobby$getCustomMaxRenderDistance(CallbackInfoReturnable<Float> cir) {
        GameSettings.Options self = (GameSettings.Options) (Object) this;
        if (self == GameSettings.Options.RENDER_DISTANCE) {
            cir.setReturnValue(1816.0F); // Always allow up to 1816
        }
    }
}

package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles game settings synchronization with Bobby config
 * Extends render distance slider to support Bobby's extended range
 */
@Mixin(GameSettings.class)
public class GameSettingsMixin {
    @Shadow public int renderDistanceChunks;
    
    @Inject(method = "setOptionValue", at = @At("RETURN"))
    private void syncConfig(GameSettings.Options option, int value, CallbackInfo ci) {
        if (option == GameSettings.Options.RENDER_DISTANCE) {
            BobbyConfig.renderDistance = renderDistanceChunks;
            ConfigManager.sync("bobby", Config.Type.INSTANCE);
        }
    }
    
    @Inject(method = "loadOptions", at = @At("RETURN"))
    private void loadFromConfig(CallbackInfo ci) {
        if (BobbyConfig.renderDistance >= 2 && BobbyConfig.renderDistance <= 1816) {
            renderDistanceChunks = BobbyConfig.renderDistance;
        }
    }
    
    @Inject(method = "saveOptions", at = @At("HEAD"))
    private void syncBeforeSave(CallbackInfo ci) {
        // Ensure config is synced before saving
        if (renderDistanceChunks != BobbyConfig.renderDistance) {
            BobbyConfig.renderDistance = renderDistanceChunks;
            ConfigManager.sync("bobby", Config.Type.INSTANCE);
        }
    }
    
    @Mixin(GameSettings.Options.class)
    public static class OptionsMixin {
        
        @Inject(method = "getValueMax", at = @At("HEAD"), cancellable = true)
        private void setMaxRenderDistance(CallbackInfoReturnable<Float> cir) {
            GameSettings.Options self = (GameSettings.Options) (Object) this;
            if (self == GameSettings.Options.RENDER_DISTANCE) {
                cir.setReturnValue(1816.0F);
            }
        }
    }
}

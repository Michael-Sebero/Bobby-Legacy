package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.common.config.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameSettings.class)
public class GameSettingsMixin {
    @Shadow public int renderDistanceChunks;
    
    // Sync slider changes to config
    @Inject(method = "setOptionValue", at = @At("RETURN"))
    private void bobby$syncRenderDistanceToConfig(GameSettings.Options settingsOption, int value, CallbackInfo ci) {
        if (settingsOption == GameSettings.Options.RENDER_DISTANCE) {
            // Update the config value to match slider
            BobbyConfig.maxRenderDistance = renderDistanceChunks;
            // Sync config to disk
            ConfigManager.sync("bobby", Config.Type.INSTANCE);
        }
    }
    
    // On load, use the config value as the starting render distance
    @Inject(method = "loadOptions", at = @At("RETURN"))
    private void bobby$loadRenderDistanceFromConfig(CallbackInfo ci) {
        // If config has a valid value, use it
        if (BobbyConfig.maxRenderDistance >= 2 && BobbyConfig.maxRenderDistance <= 1816) {
            // Only override if it's different from what was loaded
            if (renderDistanceChunks != BobbyConfig.maxRenderDistance) {
                renderDistanceChunks = BobbyConfig.maxRenderDistance;
            }
        }
        
        // Clamp just in case
        if (renderDistanceChunks > 1816) {
            renderDistanceChunks = 1816;
        }
        if (renderDistanceChunks < 2) {
            renderDistanceChunks = 2;
        }
    }
    
    // Before saving, sync config
    @Inject(method = "saveOptions", at = @At("HEAD"))
    private void bobby$syncConfigBeforeSave(CallbackInfo ci) {
        // Update config to match current render distance
        if (renderDistanceChunks >= 2 && renderDistanceChunks <= 1816) {
            BobbyConfig.maxRenderDistance = renderDistanceChunks;
            ConfigManager.sync("bobby", Config.Type.INSTANCE);
        }
    }
}

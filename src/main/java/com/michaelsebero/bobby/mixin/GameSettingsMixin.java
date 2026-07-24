package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameSettings.class)
public class GameSettingsMixin {
    @Shadow public int renderDistanceChunks;

    @Inject(method = "setOptionValue", at = @At("RETURN"))
    private void syncConfig(GameSettings.Options option, int value, CallbackInfo ci) {
        if (option != GameSettings.Options.RENDER_DISTANCE) {
            return;
        }

        /**
         * FIX (OptiFine render-distance clamp fighting Bobby's config): vanilla's
         * RENDER_DISTANCE option enforces its own hardcoded max (32 in 1.12.2) on every
         * single call to this method - including calls Bobby never asked for. OptiFine
         * routes both its own video-settings screen and the vanilla F3+F render-distance
         * hotkey through this exact setOptionValue() method, and either can invoke it
         * even when the user hasn't deliberately chosen to shrink their configured
         * distance (simply opening/closing OptiFine's video settings screen is enough -
         * see sp614x/optifine issue #7675, and multiple user reports of the slider
         * "snapping back" on its own). BobbyConfig.renderDistance can legitimately be
         * set far above that ceiling (up to 1816), so any such call clamps
         * renderDistanceChunks straight down to ~32 - and the old code here faithfully
         * copied that clamped value into BobbyConfig.renderDistance, silently
         * overwriting the user's real setting. Downstream, ChunkManager.updateChunks()
         * used to re-derive its working render distance from that same corrupted field,
         * so once it dropped to at or below simulationDistance, shouldLoadChunk() could
         * never be satisfied and Bobby stopped caching chunks entirely - with OptiFine
         * installed, no crash, and nothing in the log to point at why.
         *
         * If the field comes back at/above the vanilla ceiling while Bobby's config
         * already wants more, treat that as the vanilla clamp firing rather than
         * deliberate user intent, and restore Bobby's configured value onto the field
         * instead of accepting the clamp. A genuine reduction below the ceiling (the
         * user actually dragging the slider down themselves) still flows through to the
         * sync below untouched.
         */
        int vanillaMax = (int) GameSettings.Options.RENDER_DISTANCE.getValueMax();
        if (renderDistanceChunks >= vanillaMax && BobbyConfig.renderDistance > vanillaMax) {
            renderDistanceChunks = BobbyConfig.renderDistance;
            return;
        }

        if (renderDistanceChunks != BobbyConfig.renderDistance) {
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
}

package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.server.management.PlayerChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkMap.class)
public class PlayerChunkMapMixin {

    @Shadow private int playerViewRadius;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (BobbyConfig.enabled) {
            int simDist = Math.max(2, Math.min(BobbyConfig.simulationDistance, 32));
            this.playerViewRadius = simDist;
        }
    }

    /**
     * FIX: Original code set playerViewRadius directly and cancelled, which bypassed
     * all of vanilla's update logic in setPlayerViewRadius (chunk resending, player
     * tracking updates, etc.). The fix: when the requested radius differs from our
     * desired simulationDistance, cancel the current call and re-invoke the method
     * with the correct value. The recursive call will see radius == simDist, skip
     * this branch, and execute the full vanilla body — no infinite recursion.
     */
    @Inject(method = "setPlayerViewRadius", at = @At("HEAD"), cancellable = true)
    private void enforceSimulationDistance(int radius, CallbackInfo ci) {
        if (!BobbyConfig.enabled) {
            return;
        }

        int simDist = Math.max(2, Math.min(BobbyConfig.simulationDistance, 32));

        if (radius != simDist) {
            ci.cancel();
            // Re-invoke with the correct radius so vanilla update logic still runs.
            ((PlayerChunkMap) (Object) this).setPlayerViewRadius(simDist);
        }
        // If radius == simDist, fall through and let vanilla run normally.
    }
}

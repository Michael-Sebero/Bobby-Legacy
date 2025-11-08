package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.Bobby;
import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.server.management.PlayerChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Force the server to respect Bobby's simulation distance
 * This is critical - it prevents the server from loading chunks that should be fake
 */
@Mixin(PlayerChunkMap.class)
public class PlayerChunkMapMixin {
    
    @Shadow private int playerViewRadius;
    
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (BobbyConfig.enabled) {
            int simDist = Math.max(2, Math.min(BobbyConfig.simulationDistance, 32));
            this.playerViewRadius = simDist;
            Bobby.LOGGER.info("Initialized PlayerChunkMap with simulation distance: {}", simDist);
        }
    }
    
    @Inject(method = "setPlayerViewRadius", at = @At("HEAD"), cancellable = true)
    private void enforceSimulationDistance(int radius, CallbackInfo ci) {
        if (!BobbyConfig.enabled) {
            return;
        }
        
        // Force the server to only fully load chunks within simulation distance
        int simDist = Math.max(2, Math.min(BobbyConfig.simulationDistance, 32));
        
        // Don't let the game override our simulation distance
        if (radius != simDist) {
            Bobby.LOGGER.debug("Blocking view radius change from {} to {}, enforcing {}", 
                this.playerViewRadius, radius, simDist);
            this.playerViewRadius = simDist;
            ci.cancel();
        }
    }
}

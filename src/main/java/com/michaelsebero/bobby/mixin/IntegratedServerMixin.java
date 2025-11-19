package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void enforceSimulationDistance(CallbackInfo ci) {
        if (!BobbyConfig.enabled) {
            return;
        }
        
        IntegratedServer server = (IntegratedServer) (Object) this;
        
        for (WorldServer world : server.worlds) {
            if (world != null) {
                PlayerChunkMap chunkMap = world.getPlayerChunkMap();
                if (chunkMap != null) {
                    chunkMap.setPlayerViewRadius(BobbyConfig.simulationDistance);
                }
            }
        }
    }
}

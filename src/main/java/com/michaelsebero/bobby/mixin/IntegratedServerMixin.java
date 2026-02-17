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

    /**
     * FIX (Bug 6): Original called setPlayerViewRadius(simulationDistance) unconditionally
     * on every server tick (20 times/second). PlayerChunkMapMixin has a guard to skip work
     * when the radius is already correct, but we still incur the method call overhead across
     * all loaded dimensions every tick.
     *
     * Cache the last value we applied. Only call setPlayerViewRadius when it has actually
     * changed (e.g. the player updated the config at runtime). Use -1 as sentinel so the
     * first tick always applies the value, ensuring the server starts at simulationDistance.
     */
    private int bobby$lastAppliedSimDist = -1;

    @Inject(method = "tick", at = @At("HEAD"))
    private void enforceSimulationDistance(CallbackInfo ci) {
        if (!BobbyConfig.enabled) {
            return;
        }

        int simDist = Math.max(2, Math.min(BobbyConfig.simulationDistance, 32));

        // Only update when the configured value has changed
        if (simDist == bobby$lastAppliedSimDist) {
            return;
        }

        bobby$lastAppliedSimDist = simDist;

        IntegratedServer server = (IntegratedServer) (Object) this;

        for (WorldServer world : server.worlds) {
            if (world != null) {
                PlayerChunkMap chunkMap = world.getPlayerChunkMap();
                if (chunkMap != null) {
                    chunkMap.setPlayerViewRadius(simDist);
                }
            }
        }
    }
}

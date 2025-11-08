package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.Bobby;
import com.michaelsebero.bobby.ChunkManager;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public class NetHandlerPlayClientMixin {
    @Shadow private WorldClient world;

    /**
     * When server sends a chunk, save it for Bobby's cache
     * CRITICAL: Use TAIL to ensure chunk is fully loaded and accessible
     */
    @Inject(method = "handleChunkData", at = @At("TAIL"))
    private void onChunkData(SPacketChunkData data, CallbackInfo ci) {
        try {
            if (world == null) {
                Bobby.LOGGER.debug("World is null in handleChunkData");
                return;
            }
            
            if (!(world.getChunkProvider() instanceof IChunkProviderClient)) {
                Bobby.LOGGER.debug("ChunkProvider is not IChunkProviderClient");
                return;
            }
            
            ChunkManager manager = ((IChunkProviderClient) world.getChunkProvider()).getBobbyChunkManager();
            if (manager == null) {
                Bobby.LOGGER.debug("ChunkManager is null");
                return;
            }
            
            int x = data.getChunkX();
            int z = data.getChunkZ();
            
            Bobby.LOGGER.debug("Received chunk data for ({}, {}), attempting to save", x, z);
            
            // Get the chunk that was just loaded
            net.minecraft.world.chunk.Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
            if (chunk != null) {
                Bobby.LOGGER.debug("Chunk ({}, {}) is loaded, saving to Bobby", x, z);
                manager.load(x, z, chunk);
            } else {
                Bobby.LOGGER.warn("Chunk ({}, {}) data received but chunk not loaded!", x, z);
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Error in handleChunkData", e);
        }
    }
}

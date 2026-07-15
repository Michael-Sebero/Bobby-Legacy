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

    @Inject(method = "handleChunkData", at = @At("TAIL"))
    private void onChunkData(SPacketChunkData data, CallbackInfo ci) {
        try {
            if (world == null) {
                return;
            }
            
            if (!(world.getChunkProvider() instanceof IChunkProviderClient)) {
                return;
            }
            
            ChunkManager manager = ((IChunkProviderClient) world.getChunkProvider()).getBobbyChunkManager();
            if (manager == null) {
                return;
            }
            
            int x = data.getChunkX();
            int z = data.getChunkZ();
            
            net.minecraft.world.chunk.Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
            if (chunk != null) {
                manager.load(x, z, chunk);
            }
        } catch (Exception e) {
            /**
             * FIX: this used to be a bare "// Silent fail" comment with no logging
             * at all - the only place in the codebase that swallowed an exception
             * without even a debug-level log line. Every other catch block here logs
             * at minimum Bobby.LOGGER.debug(...), which costs nothing at the default
             * log level but means a real problem isn't invisible if this ever needs
             * diagnosing.
             */
            Bobby.LOGGER.debug("Failed to cache chunk data for chunk (" + data.getChunkX() + ", " + data.getChunkZ() + ")", e);
        }
    }
}

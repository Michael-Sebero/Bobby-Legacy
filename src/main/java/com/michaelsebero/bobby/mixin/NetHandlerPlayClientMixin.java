package com.michaelsebero.bobby.mixin;

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
            // Silent fail
        }
    }
}

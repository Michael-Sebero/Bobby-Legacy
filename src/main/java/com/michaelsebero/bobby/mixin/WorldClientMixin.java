package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.ChunkManager;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldClient.class)
public abstract class WorldClientMixin extends World {

    protected WorldClientMixin(ISaveHandler saveHandlerIn, WorldInfo info, WorldProvider providerIn, Profiler profilerIn, boolean client) {
        super(saveHandlerIn, info, providerIn, profilerIn, client);
    }

    @Inject(method = "sendQuittingDisconnectingPacket", at = @At("HEAD"))
    private void onWorldUnload(CallbackInfo ci) {
        if (this.getChunkProvider() instanceof IChunkProviderClient) {
            ChunkManager manager = ((IChunkProviderClient) this.getChunkProvider()).getBobbyChunkManager();
            if (manager != null) {
                manager.shutdown();
            }
        }
    }
}

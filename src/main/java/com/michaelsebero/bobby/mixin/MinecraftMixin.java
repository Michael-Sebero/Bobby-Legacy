package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.ChunkManager;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    
    /**
     * Inject into runTickKeyboard which is called every frame in 1.12.2
     * This is more reliable than trying to find the main game loop method
     */
    @Inject(method = "runTickKeyboard", at = @At("HEAD"))
    private void onRunTickKeyboard(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        WorldClient world = mc.world;
        
        if (world != null && world.getChunkProvider() instanceof IChunkProviderClient) {
            ChunkManager manager = ((IChunkProviderClient) world.getChunkProvider()).getBobbyChunkManager();
            if (manager != null) {
                manager.tick();
            }
        }
    }
    
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void onShutdown(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        WorldClient world = mc.world;
        
        if (world != null && world.getChunkProvider() instanceof IChunkProviderClient) {
            ChunkManager manager = ((IChunkProviderClient) world.getChunkProvider()).getBobbyChunkManager();
            if (manager != null) {
                manager.shutdown();
            }
        }
    }
}

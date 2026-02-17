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
     * FIX (Bug 1): Was injecting into runTickKeyboard which is called every RENDER FRAME
     * (potentially 60-144+ times/second). ChunkManager.tick() uses tickCounter to sequence
     * events (e.g. tick 40 = initial restoration, every 10 ticks = chunk update scan).
     * At 60fps these fire 3-7x too fast, causing the executor to be flooded with tasks
     * and the restoration to trigger after ~0.67s instead of the intended ~2 seconds.
     *
     * runTick() is called at the game tick rate (20 TPS), which is what tick() expects.
     */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
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
        Minecraft mc = (Minecraft) (Object) this;
        WorldClient world = mc.world;

        if (world != null && world.getChunkProvider() instanceof IChunkProviderClient) {
            ChunkManager manager = ((IChunkProviderClient) world.getChunkProvider()).getBobbyChunkManager();
            if (manager != null) {
                // FIX (Bug 3): ChunkManager.shutdown() is now idempotent (guarded by isShutdown flag),
                // so calling it from both here and WorldClientMixin.sendQuittingDisconnectingPacket
                // is safe. The first call does all the work; subsequent calls are no-ops.
                manager.shutdown();
            }
        }
    }
}

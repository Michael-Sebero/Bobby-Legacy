package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.FakeChunkManager;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    private long bobby$lastUpdateTime = 0;
    private static final long BOBBY$MIN_UPDATE_INTERVAL_MS = 50;

    @Inject(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V",
            ordinal = 0
        )
    )
    private void bobby$updateBeforeTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        
        if (mc.world == null) {
            return;
        }
        
        FakeChunkManager bobbyChunkManager = ((IChunkProviderClient) mc.world.getChunkProvider()).getBobbyChunkManager();
        if (bobbyChunkManager == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - bobby$lastUpdateTime < BOBBY$MIN_UPDATE_INTERVAL_MS) {
            return;
        }
        bobby$lastUpdateTime = currentTime;

        mc.profiler.startSection("bobbyUpdate");

        int maxFps = mc.gameSettings.limitFramerate;
        if (maxFps == 0 || maxFps == GameSettings.Options.FRAMERATE_LIMIT.getValueMax()) {
            maxFps = 120;
        }
        
        long frameTimeNanos = 1_000_000_000L / maxFps;
        long budgetNanos = frameTimeNanos / 5;
        long timeLimit = System.nanoTime() + budgetNanos;
        
        bobbyChunkManager.update(() -> System.nanoTime() < timeLimit);

        mc.profiler.endSection();
    }
    
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void bobby$shutdown(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        
        if (mc.world != null) {
            FakeChunkManager manager = ((IChunkProviderClient) mc.world.getChunkProvider()).getBobbyChunkManager();
            if (manager != null) {
                manager.shutdown();
            }
        }
    }
}

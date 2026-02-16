package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.Bobby;
import com.michaelsebero.bobby.BobbyConfig;
import com.michaelsebero.bobby.ChunkManager;
import com.michaelsebero.bobby.ChunkStorage;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import javax.annotation.Nullable;
import java.io.File;

@Mixin(ChunkProviderClient.class)
public class ChunkProviderClientMixin implements IChunkProviderClient {
    @Shadow @Final private Chunk blankChunk;
    @Shadow @Final private World world;
    
    @Nullable
    private ChunkManager bobby$manager;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(World world, CallbackInfo ci) {
        if (!BobbyConfig.enabled) return;
        
        try {
            String worldName = getWorldName();
            int dimension = world.provider.getDimension();
            
            File bobbyDir = Minecraft.getMinecraft().gameDir.toPath()
                .resolve(".bobby")
                .resolve(worldName)
                .resolve(String.valueOf(dimension))
                .toFile();
            
            if (!bobbyDir.exists() && !bobbyDir.mkdirs()) {
                Bobby.LOGGER.error("Failed to create Bobby directory: " + bobbyDir);
                return;
            }
            
            ChunkStorage storage = ChunkStorage.create(bobbyDir);
            bobby$manager = new ChunkManager((WorldClient) world, storage);
            Bobby.LOGGER.info("Bobby initialized for " + worldName + " (dimension " + dimension + ")");
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to initialize Bobby", e);
        }
    }

    @Override
    public ChunkManager getBobbyChunkManager() {
        return bobby$manager;
    }

    @Inject(method = "provideChunk", at = @At("RETURN"), cancellable = true)
    private void onProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (cir.getReturnValue() == blankChunk && bobby$manager != null) {
            Chunk fake = bobby$manager.get(x, z);
            if (fake != null) {
                cir.setReturnValue(fake);
            }
        }
    }

    @Inject(method = "loadChunk", at = @At("RETURN"))
    private void onLoadChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (bobby$manager != null && cir.getReturnValue() != null) {
            bobby$manager.load(x, z, cir.getReturnValue());
        }
    }

    @Inject(method = "unloadChunk", at = @At("HEAD"))
    private void onUnloadChunk(int x, int z, CallbackInfo ci) {
        if (bobby$manager != null) {
            bobby$manager.unload(x, z);
        }
    }

    @Inject(method = "makeString", at = @At("RETURN"), cancellable = true)
    private void onMakeString(CallbackInfoReturnable<String> cir) {
        if (bobby$manager != null && BobbyConfig.showDebug) {
            cir.setReturnValue(cir.getReturnValue() + " " + bobby$manager.getDebugInfo());
        }
    }

    private String getWorldName() {
        if (Minecraft.getMinecraft().getIntegratedServer() != null) {
            String name = Minecraft.getMinecraft().getIntegratedServer().getWorldName();
            return sanitizeFileName(name);
        }
        
        if (Minecraft.getMinecraft().getCurrentServerData() != null) {
            String serverIP = Minecraft.getMinecraft().getCurrentServerData().serverIP;
            return sanitizeFileName(serverIP.replace(':', '_'));
        }
        
        return "unknown";
    }
    
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        
        return name.replaceAll("[/\\\\:*?\"<>| ]", "_");
    }
}

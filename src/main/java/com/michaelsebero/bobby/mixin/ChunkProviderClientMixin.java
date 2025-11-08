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
            
            Bobby.LOGGER.info("Bobby storage path: {}", bobbyDir.getAbsolutePath());
            Bobby.LOGGER.info("World name: '{}', Dimension: {}", worldName, dimension);
            
            // Ensure directory exists
            if (!bobbyDir.exists()) {
                Bobby.LOGGER.info("Creating Bobby storage directory...");
                boolean created = bobbyDir.mkdirs();
                Bobby.LOGGER.info("Directory creation {}", created ? "successful" : "failed");
            } else {
                Bobby.LOGGER.info("Bobby storage directory exists: {}", bobbyDir.exists());
                Bobby.LOGGER.info("Directory contents: {} files", 
                    bobbyDir.listFiles() != null ? bobbyDir.listFiles().length : 0);
            }
            
            ChunkStorage storage = ChunkStorage.create(bobbyDir);
            bobby$manager = new ChunkManager((WorldClient) world, storage);
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to initialize ChunkManager", e);
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
        // For singleplayer
        if (Minecraft.getMinecraft().getIntegratedServer() != null) {
            String name = Minecraft.getMinecraft().getIntegratedServer().getWorldName();
            Bobby.LOGGER.debug("Singleplayer world name: '{}'", name);
            return sanitizeFileName(name);
        }
        
        // For multiplayer
        if (Minecraft.getMinecraft().getCurrentServerData() != null) {
            String serverIP = Minecraft.getMinecraft().getCurrentServerData().serverIP;
            Bobby.LOGGER.debug("Multiplayer server IP: '{}'", serverIP);
            return sanitizeFileName(serverIP.replace(':', '_'));
        }
        
        Bobby.LOGGER.warn("Could not determine world name, using 'unknown'");
        return "unknown";
    }
    
    /**
     * Sanitize world name to be safe for filesystem
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        
        // Replace problematic characters with underscores
        String sanitized = name
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replace(' ', '_'); // Replace spaces too
        
        Bobby.LOGGER.debug("Sanitized '{}' to '{}'", name, sanitized);
        return sanitized;
    }
}

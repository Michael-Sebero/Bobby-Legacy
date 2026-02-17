package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.Bobby;
import com.michaelsebero.bobby.BobbyConfig;
import com.michaelsebero.bobby.ChunkManager;
import com.michaelsebero.bobby.ChunkStorage;
import com.michaelsebero.bobby.FakeChunk;
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
        if (bobby$manager == null) return;

        Chunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        /**
         * FIX (Bug 4): Without this guard, if a FakeChunk is somehow returned by loadChunk
         * (e.g. due to a race where provideChunk fires before the real chunk is set),
         * it would be serialized back to disk, overwriting the real saved data with an
         * empty/incorrect chunk. FakeChunks are read-only views and must never be saved.
         */
        if (chunk instanceof FakeChunk) return;

        bobby$manager.load(x, z, chunk);
    }

    /**
     * FIX (Flicker Bug): The previous implementation called manager.unload(x, z) here,
     * which immediately removed the FakeChunk from the fakeChunks map. This created a
     * blank-chunk gap: after vanilla's unloadChunk() ran, provideChunk() returned
     * blankChunk until updateChunks() re-queued the position (up to 10 ticks later) and
     * loadAsync() finished deserializing it (several more ticks on the executor). The
     * player saw the chunk pop in and out — the visible "flicker".
     *
     * Fix: call scheduleReload(x, z) instead. scheduleReload only queues a load if no
     * FakeChunk is already present, so:
     *   - If a FakeChunk exists (chunk was previously cached and shown): it stays in the
     *     map, provideChunk() returns it immediately, zero gap.
     *   - If no FakeChunk exists yet (first time the server is dropping this chunk):
     *     a load is queued so the FakeChunk appears as soon as deserialization completes.
     *
     * Out-of-range FakeChunk eviction is now handled inside ChunkManager.updateChunks(),
     * which runs every 10 ticks and removes entries that fall outside renderDistance.
     */
    @Inject(method = "unloadChunk", at = @At("HEAD"))
    private void onUnloadChunk(int x, int z, CallbackInfo ci) {
        if (bobby$manager != null) {
            bobby$manager.scheduleReload(x, z);
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

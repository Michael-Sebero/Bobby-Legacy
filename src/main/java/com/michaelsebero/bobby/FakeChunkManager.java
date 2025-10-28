package com.michaelsebero.bobby;

import com.michaelsebero.bobby.BobbyConfig;
import com.michaelsebero.bobby.compat.IChunkStatusListener;
import com.michaelsebero.bobby.ext.ChunkProviderClientExt;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.chunk.Chunk;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final Minecraft client = Minecraft.getMinecraft();
    private static final int SAVE_INTERVAL_TICKS = 20 * 60; // 1 minute
    private static final int MAX_CHUNKS_PER_TICK = 4; // Limit chunk processing per tick
    private static final int LOADING_THREAD_POOL_SIZE = 4; // Reduced from 8

    private final WorldClient world;
    private final ChunkProviderClient clientChunkManager;
    private final ChunkProviderClientExt clientChunkManagerExt;
    private int ticksSinceLastSave;
    private final FakeChunkStorage storage;
    private final FakeChunkStorage fallbackStorage;

    // Use thread-safe collections more efficiently
    private final Long2ObjectMap<Chunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private int centerX, centerZ, viewDistance;
    private final Long2LongMap toBeUnloaded = new Long2LongOpenHashMap();
    
    // Use priority queue for better unload ordering
    private final ConcurrentLinkedDeque<Pair<Long, Long>> unloadQueue = new ConcurrentLinkedDeque<>();

    // Optimized thread pool with better configuration
    private static final ExecutorService loadExecutor = new ThreadPoolExecutor(
        2, // core pool size
        LOADING_THREAD_POOL_SIZE, // max pool size
        60L, TimeUnit.SECONDS, // keep alive time
        new LinkedBlockingQueue<>(256), // bounded queue to prevent memory issues
        new DefaultThreadFactory("bobby-loading", true),
        new ThreadPoolExecutor.CallerRunsPolicy() // backpressure handling
    );
    
    private final ConcurrentHashMap<Long, LoadingJob> loadingJobs = new ConcurrentHashMap<>();
    
    // Cache for position calculations
    private final LongSet loadedChunkPositions = new LongOpenHashSet();
    
    // Batch processing optimization
    private final LongList chunksToLoad = new LongArrayList();
    private final LongList chunksToUnload = new LongArrayList();

    public FakeChunkManager(WorldClient world, ChunkProviderClient clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;
        this.clientChunkManagerExt = (ChunkProviderClientExt) clientChunkManager;

        long seedHash = 123456;
        DimensionType worldKey = world.provider.getDimensionType();
        Path storagePath = client.gameDir
                .toPath()
                .resolve(".bobby")
                .resolve(getCurrentWorldOrServerName())
                .resolve(seedHash + "")
                .resolve(worldKey.getName());

        storage = FakeChunkStorage.getFor(storagePath.toFile(), null);

        FakeChunkStorage fallbackStorage = null;
        if (client.getSaveLoader().canLoadWorld(FALLBACK_LEVEL_NAME)) {
            File worldDirectory = client.getSaveLoader().getSaveLoader(FALLBACK_LEVEL_NAME, false).getWorldDirectory();
            if (world.provider.getSaveFolder() != null)
                worldDirectory = new File(worldDirectory, world.provider.getSaveFolder());
            File regionDirectory = new File(worldDirectory, "region");
            fallbackStorage = FakeChunkStorage.getFor(regionDirectory, null);
        }
        this.fallbackStorage = fallbackStorage;
    }

    public Chunk getChunk(int x, int z) {
        return fakeChunks.get(ChunkPos.asLong(x, z));
    }

    public FakeChunkStorage getStorage() {
        return storage;
    }

    public void update(BooleanSupplier shouldKeepTicking) {
        // Periodic save optimization - async execution
        if (++ticksSinceLastSave > SAVE_INTERVAL_TICKS) {
            loadExecutor.execute(() -> storage.writeNextIO());
            ticksSinceLastSave = 0;
        }

        EntityPlayerSP player = client.player;
        if (player == null) {
            return;
        }

        long time = System.currentTimeMillis(); // Use currentTimeMillis instead of nanoTime for timestamps

        int oldCenterX = this.centerX;
        int oldCenterZ = this.centerZ;
        int oldViewDistance = this.viewDistance;
        int newCenterX = player.chunkCoordX;
        int newCenterZ = player.chunkCoordZ;
        int newViewDistance = client.gameSettings.renderDistanceChunks;
        
        if (oldCenterX != newCenterX || oldCenterZ != newCenterZ || oldViewDistance != newViewDistance) {
            updateChunkLoadingRegion(oldCenterX, oldCenterZ, oldViewDistance, 
                                     newCenterX, newCenterZ, newViewDistance, time);
            
            this.centerX = newCenterX;
            this.centerZ = newCenterZ;
            this.viewDistance = newViewDistance;
        }

        // Process unload queue with throttling
        processUnloadQueue(time, shouldKeepTicking);

        // Process loading jobs with limit
        processLoadingJobs(shouldKeepTicking);
    }

    private void updateChunkLoadingRegion(int oldCenterX, int oldCenterZ, int oldViewDistance,
                                          int newCenterX, int newCenterZ, int newViewDistance, long time) {
        chunksToLoad.clear();
        chunksToUnload.clear();
        
        // Calculate chunks to unload
        int minOldX = oldCenterX - oldViewDistance;
        int maxOldX = oldCenterX + oldViewDistance;
        int minOldZ = oldCenterZ - oldViewDistance;
        int maxOldZ = oldCenterZ + oldViewDistance;
        
        int minNewX = newCenterX - newViewDistance;
        int maxNewX = newCenterX + newViewDistance;
        int minNewZ = newCenterZ - newViewDistance;
        int maxNewZ = newCenterZ + newViewDistance;
        
        // Batch unload operations
        for (int x = minOldX; x <= maxOldX; x++) {
            boolean xOutside = x < minNewX || x > maxNewX;
            for (int z = minOldZ; z <= maxOldZ; z++) {
                if (xOutside || z < minNewZ || z > maxNewZ) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    chunksToUnload.add(chunkPos);
                }
            }
        }
        
        // Process unloads in batch
        for (long chunkPos : chunksToUnload) {
            int x = ChunkPosHelper.getPackedX(chunkPos);
            int z = ChunkPosHelper.getPackedZ(chunkPos);
            cancelLoad(x, z);
            toBeUnloaded.put(chunkPos, time);
            unloadQueue.add(Pair.of(chunkPos, time));
        }
        
        // Batch load operations
        for (int x = minNewX; x <= maxNewX; x++) {
            boolean xOutside = x < minOldX || x > maxOldX;
            for (int z = minNewZ; z <= maxNewZ; z++) {
                if (xOutside || z < minOldZ || z > maxOldZ) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    chunksToLoad.add(chunkPos);
                }
            }
        }
        
        // Process loads in batch
        for (long chunkPos : chunksToLoad) {
            int x = ChunkPosHelper.getPackedX(chunkPos);
            int z = ChunkPosHelper.getPackedZ(chunkPos);
            
            toBeUnloaded.remove(chunkPos);
            
            if (clientChunkManager.getLoadedChunk(x, z) != null) {
                continue;
            }
            
            // Only queue if we're not already loading it
            if (!loadingJobs.containsKey(chunkPos)) {
                LoadingJob loadingJob = new LoadingJob(x, z);
                loadingJobs.put(chunkPos, loadingJob);
                loadExecutor.execute(loadingJob);
            }
        }
    }

    private void processUnloadQueue(long currentTime, BooleanSupplier shouldKeepTicking) {
        long unloadTime = currentTime - BobbyConfig.unloadDelaySecs * 1000L;
        int processed = 0;
        
        while (processed < MAX_CHUNKS_PER_TICK) {
            Pair<Long, Long> next = unloadQueue.pollFirst();
            if (next == null) {
                break;
            }
            
            long chunkPos = next.getLeft();
            long queuedTime = next.getRight();

            if (queuedTime > unloadTime) {
                unloadQueue.addFirst(next);
                break;
            }

            long actualQueuedTime = toBeUnloaded.remove(chunkPos);
            if (actualQueuedTime != queuedTime) {
                if (actualQueuedTime != 0) {
                    toBeUnloaded.put(chunkPos, actualQueuedTime);
                }
                continue;
            }

            unload(ChunkPosHelper.getPackedX(chunkPos), ChunkPosHelper.getPackedZ(chunkPos), false);
            processed++;
            
            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }
    }

    private void processLoadingJobs(BooleanSupplier shouldKeepTicking) {
        int processed = 0;
        java.util.Iterator<LoadingJob> iter = loadingJobs.values().iterator();
        
        while (iter.hasNext() && processed < MAX_CHUNKS_PER_TICK) {
            LoadingJob job = iter.next();
            
            if (job.result == null) {
                continue;
            }

            iter.remove();
            
            if (!job.cancelled) {
                client.profiler.startSection("loadFakeChunk");
                job.complete();
                client.profiler.endSection();
                processed++;
            }

            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }
    }

    private @Nullable Pair<NBTTagCompound, FakeChunkStorage> loadTag(int x, int z) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        try {
            NBTTagCompound tag = storage.loadTag(chunkPos);
            if (tag != null) {
                return Pair.of(tag, storage);
            }
            if (fallbackStorage != null) {
                tag = fallbackStorage.loadTag(chunkPos);
                if (tag != null) {
                    return Pair.of(tag, fallbackStorage);
                }
            }
        } catch (IOException e) {
            // Log error but don't crash
            System.err.println("Error loading chunk at " + x + ", " + z + ": " + e.getMessage());
        }
        return null;
    }

    public void load(int x, int z, NBTTagCompound tag, FakeChunkStorage storage) {
        Supplier<Chunk> chunkSupplier = storage.deserialize(new ChunkPos(x, z), tag, world);
        if (chunkSupplier == null) {
            return;
        }
        load(x, z, chunkSupplier.get());
    }

    protected void load(int x, int z, Chunk chunk) {
        long pos = ChunkPos.asLong(x, z);
        fakeChunks.put(pos, chunk);
        loadedChunkPositions.add(pos);

        world.markBlockRangeForRenderUpdate(x << 4, 0, z << 4, (x << 4) + 15, 256, (z << 4) + 15);

        IChunkStatusListener listener = clientChunkManagerExt.bobby_getListener();
        if (listener != null) {
            listener.onChunkAdded(x, z);
        }
    }

    public boolean unload(int x, int z, boolean willBeReplaced) {
        cancelLoad(x, z);
        long pos = ChunkPos.asLong(x, z);
        Chunk chunk = fakeChunks.remove(pos);
        
        if (chunk != null) {
            loadedChunkPositions.remove(pos);
            chunk.onUnload();

            // Batch removal optimization
            world.loadedTileEntityList.removeAll(chunk.getTileEntityMap().values());
            world.tickableTileEntities.removeAll(chunk.getTileEntityMap().values());

            if (!willBeReplaced) {
                IChunkStatusListener listener = clientChunkManagerExt.bobby_getListener();
                if (listener != null) {
                    listener.onChunkRemoved(x, z);
                }
            }
            return true;
        }

        return false;
    }

    private void cancelLoad(int x, int z) {
        LoadingJob job = loadingJobs.remove(ChunkPos.asLong(x, z));
        if (job != null) {
            job.cancelled = true;
        }
    }

    private static String getCurrentWorldOrServerName() {
        IntegratedServer integratedServer = client.getIntegratedServer();
        if (integratedServer != null) {
            return integratedServer.getWorldName();
        }

        ServerData serverInfo = client.getCurrentServerData();
        if (serverInfo != null) {
            return serverInfo.serverIP.replace(':', '_');
        }

        if (client.isConnectedToRealms()) {
            return "realms";
        }

        return "unknown";
    }

    public String getDebugString() {
        return String.format("F: %d L: %d U: %d Q: %d", 
            fakeChunks.size(), 
            loadingJobs.size(), 
            toBeUnloaded.size(),
            ((ThreadPoolExecutor)loadExecutor).getQueue().size());
    }
    
    public void shutdown() {
        loadExecutor.shutdown();
        try {
            if (!loadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            loadExecutor.shutdownNow();
        }
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private volatile boolean cancelled;
        private volatile Optional<Supplier<Chunk>> result;

        public LoadingJob(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            
            try {
                Pair<NBTTagCompound, FakeChunkStorage> tagPair = loadTag(x, z);
                if (tagPair != null && !cancelled) {
                    Supplier<Chunk> supplier = tagPair.getRight()
                        .deserialize(new ChunkPos(x, z), tagPair.getLeft(), world);
                    result = Optional.ofNullable(supplier);
                } else {
                    result = Optional.empty();
                }
            } catch (Exception e) {
                System.err.println("Error in loading job for chunk " + x + ", " + z + ": " + e.getMessage());
                result = Optional.empty();
            }
        }

        public void complete() {
            if (result != null) {
                result.ifPresent(supplier -> {
                    if (!cancelled) {
                        load(x, z, supplier.get());
                    }
                });
            }
        }
    }
}

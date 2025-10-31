package com.michaelsebero.bobby;

import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.*;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final Minecraft client = Minecraft.getMinecraft();
    private static final int SAVE_INTERVAL_TICKS = 20 * 60;

    private final WorldClient world;
    private final ChunkProviderClient clientChunkManager;
    private int ticksSinceLastSave;
    private final FakeChunkStorage storage;
    private final FakeChunkStorage fallbackStorage;

    private final Long2ObjectMap<Chunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private int centerX, centerZ, viewDistance;
    private final Long2LongMap toBeUnloaded = Long2LongMaps.synchronize(new Long2LongOpenHashMap());
    private final PriorityBlockingQueue<UnloadTask> unloadQueue = new PriorityBlockingQueue<>(256, 
        Comparator.comparingLong(UnloadTask::getUnloadTime));

    // Instance-specific executors that won't be shared
    private final ExecutorService loadExecutor;
    private final ScheduledExecutorService saveExecutor;
    
    private final ConcurrentHashMap<Long, LoadingJob> loadingJobs = new ConcurrentHashMap<>();
    private final BlockingQueue<LoadingJob> completedJobs = new LinkedBlockingQueue<>();
    
    // Thread-safe LRU cache for chunk tags
    private final Map<Long, NBTTagCompound> chunkTagCache = Collections.synchronizedMap(
        new LinkedHashMap<Long, NBTTagCompound>(BobbyConfig.maxCacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, NBTTagCompound> eldest) {
                return size() > BobbyConfig.maxCacheSize;
            }
        }
    );

    private final PerformanceMonitor perfMonitor = PerformanceMonitor.getInstance();
    private final Set<Long> chunksInLoadQueue = ConcurrentHashMap.newKeySet();
    private final Map<Long, ScheduledLoad> scheduledLoads = new ConcurrentHashMap<>();

    public FakeChunkManager(WorldClient world, ChunkProviderClient clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;

        // Create instance-specific executors
        this.loadExecutor = new ThreadPoolExecutor(
            Math.max(1, BobbyConfig.loadingThreads / 2),
            BobbyConfig.loadingThreads,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            new DefaultThreadFactory("bobby-loading-" + world.provider.getDimension(), true),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(
            new DefaultThreadFactory("bobby-saving-" + world.provider.getDimension(), true)
        );

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
        
        // Schedule periodic auto-save
        if (BobbyConfig.asyncSaving) {
            saveExecutor.scheduleAtFixedRate(() -> {
                try {
                    storage.processPendingSaves();
                    storage.writeNextIO();
                } catch (Exception e) {
                    Bobby.LOGGER.error("Error during auto-save", e);
                }
            }, BobbyConfig.autoSaveInterval, BobbyConfig.autoSaveInterval, TimeUnit.SECONDS);
        }
    }

    @Nullable
    public Chunk getChunk(int x, int z) {
        return fakeChunks.get(ChunkPos.asLong(x, z));
    }

    public FakeChunkStorage getStorage() {
        return storage;
    }

    public void update(BooleanSupplier shouldKeepTicking) {
        long startTime = System.nanoTime();
        
        try {
            if (++ticksSinceLastSave > SAVE_INTERVAL_TICKS) {
                if (BobbyConfig.asyncSaving) {
                    loadExecutor.execute(() -> {
                        storage.processPendingSaves();
                        storage.writeNextIO();
                    });
                } else {
                    storage.writeNextIO();
                }
                ticksSinceLastSave = 0;
            }

            EntityPlayerSP player = client.player;
            if (player == null) {
                return;
            }

            long time = System.currentTimeMillis();

            int oldCenterX = this.centerX;
            int oldCenterZ = this.centerZ;
            int oldViewDistance = this.viewDistance;
            int newCenterX = player.chunkCoordX;
            int newCenterZ = player.chunkCoordZ;
            int newViewDistance = Math.min(client.gameSettings.renderDistanceChunks, BobbyConfig.maxRenderDistance);
            
            if (oldCenterX != newCenterX || oldCenterZ != newCenterZ || oldViewDistance != newViewDistance) {
                updateChunkLoadingRegion(oldCenterX, oldCenterZ, oldViewDistance, 
                                         newCenterX, newCenterZ, newViewDistance, time);
                
                this.centerX = newCenterX;
                this.centerZ = newCenterZ;
                this.viewDistance = newViewDistance;
            }

            processUnloadQueue(time, shouldKeepTicking);
            processCompletedJobs(shouldKeepTicking);
            processScheduledLoads();
        } finally {
            if (BobbyConfig.enablePerformanceMonitoring) {
                perfMonitor.recordUpdateTime(System.nanoTime() - startTime);
            }
        }
    }

    private void updateChunkLoadingRegion(int oldCenterX, int oldCenterZ, int oldViewDistance,
                                          int newCenterX, int newCenterZ, int newViewDistance, long time) {
        
        int minOldX = oldCenterX - oldViewDistance;
        int maxOldX = oldCenterX + oldViewDistance;
        int minOldZ = oldCenterZ - oldViewDistance;
        int maxOldZ = oldCenterZ + oldViewDistance;
        
        int minNewX = newCenterX - newViewDistance;
        int maxNewX = newCenterX + newViewDistance;
        int minNewZ = newCenterZ - newViewDistance;
        int maxNewZ = newCenterZ + newViewDistance;
        
        // Find chunks to unload (now outside view distance)
        for (int x = minOldX; x <= maxOldX; x++) {
            boolean xOutside = x < minNewX || x > maxNewX;
            for (int z = minOldZ; z <= maxOldZ; z++) {
                if (xOutside || z < minNewZ || z > maxNewZ) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    cancelLoad(x, z);
                    
                    long unloadTime = time + (BobbyConfig.unloadDelaySecs * 1000L);
                    toBeUnloaded.put(chunkPos, unloadTime);
                    unloadQueue.offer(new UnloadTask(chunkPos, unloadTime));
                }
            }
        }
        
        // Find chunks to load, prioritized by distance from center
        List<ChunkLoadTask> loadTasks = new ArrayList<>();
        for (int x = minNewX; x <= maxNewX; x++) {
            boolean xOutside = x < minOldX || x > maxOldX;
            for (int z = minNewZ; z <= maxNewZ; z++) {
                if (xOutside || z < minOldZ || z > maxOldZ) {
                    long chunkPos = ChunkPos.asLong(x, z);
                    int distSq = (x - newCenterX) * (x - newCenterX) + (z - newCenterZ) * (z - newCenterZ);
                    loadTasks.add(new ChunkLoadTask(chunkPos, distSq));
                }
            }
        }
        
        // Sort by distance (closest first)
        loadTasks.sort(Comparator.comparingInt(t -> t.distanceSquared));
        
        // Queue loads
        for (ChunkLoadTask task : loadTasks) {
            long chunkPos = task.chunkPos;
            int x = ChunkPosHelper.getPackedX(chunkPos);
            int z = ChunkPosHelper.getPackedZ(chunkPos);
            
            // Cancel unload if it was scheduled
            toBeUnloaded.remove(chunkPos);
            
            if (clientChunkManager.getLoadedChunk(x, z) != null) {
                continue;
            }
            
            // Avoid duplicate loads
            if (!chunksInLoadQueue.add(chunkPos)) {
                continue;
            }
            
            LoadingJob loadingJob = new LoadingJob(x, z, task.distanceSquared);
            loadingJobs.put(chunkPos, loadingJob);
            loadExecutor.execute(loadingJob);
        }
    }

    private void processUnloadQueue(long currentTime, BooleanSupplier shouldKeepTicking) {
        int processed = 0;
        
        while (processed < BobbyConfig.maxChunksPerTick) {
            UnloadTask task = unloadQueue.peek();
            if (task == null || task.unloadTime > currentTime) {
                break;
            }
            
            unloadQueue.poll();
            long chunkPos = task.chunkPos;

            // Double-check if still scheduled for unload
            long scheduledTime = toBeUnloaded.get(chunkPos);
            if (scheduledTime == 0 || scheduledTime != task.unloadTime) {
                continue;
            }

            toBeUnloaded.remove(chunkPos);
            unload(ChunkPosHelper.getPackedX(chunkPos), ChunkPosHelper.getPackedZ(chunkPos), false);
            processed++;
            
            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }
    }

    private void processCompletedJobs(BooleanSupplier shouldKeepTicking) {
        int processed = 0;
        
        while (processed < BobbyConfig.maxChunksPerTick && !completedJobs.isEmpty()) {
            LoadingJob job = completedJobs.poll();
            if (job == null) break;
            
            if (!job.cancelled) {
                long startTime = System.nanoTime();
                client.profiler.startSection("loadFakeChunk");
                job.complete();
                client.profiler.endSection();
                
                if (BobbyConfig.enablePerformanceMonitoring) {
                    perfMonitor.recordLoadTime(System.nanoTime() - startTime);
                }
                processed++;
            }

            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }
    }

    private void processScheduledLoads() {
        if (scheduledLoads.isEmpty()) return;
        
        Iterator<Map.Entry<Long, ScheduledLoad>> iterator = scheduledLoads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ScheduledLoad> entry = iterator.next();
            ScheduledLoad scheduled = entry.getValue();
            
            // Only load if the real chunk hasn't been loaded yet
            if (clientChunkManager.getLoadedChunk(scheduled.x, scheduled.z) == null) {
                load(scheduled.x, scheduled.z, scheduled.tag, scheduled.storage);
            }
            iterator.remove();
        }
    }

    @Nullable
    private Pair<NBTTagCompound, FakeChunkStorage> loadTag(int x, int z) {
        long chunkPos = ChunkPos.asLong(x, z);
        
        // Check cache first
        synchronized (chunkTagCache) {
            NBTTagCompound cached = chunkTagCache.get(chunkPos);
            if (cached != null) {
                if (BobbyConfig.enablePerformanceMonitoring) {
                    perfMonitor.recordCacheHit();
                }
                return Pair.of(cached.copy(), storage);
            }
        }
        
        if (BobbyConfig.enablePerformanceMonitoring) {
            perfMonitor.recordCacheMiss();
        }
        
        try {
            NBTTagCompound tag = storage.loadTag(new ChunkPos(x, z));
            if (tag != null) {
                synchronized (chunkTagCache) {
                    chunkTagCache.put(chunkPos, tag.copy());
                }
                return Pair.of(tag, storage);
            }
            
            if (fallbackStorage != null) {
                tag = fallbackStorage.loadTag(new ChunkPos(x, z));
                if (tag != null) {
                    synchronized (chunkTagCache) {
                        chunkTagCache.put(chunkPos, tag.copy());
                    }
                    return Pair.of(tag, fallbackStorage);
                }
            }
        } catch (IOException e) {
            Bobby.LOGGER.error("Error loading chunk at {}, {}", x, z, e);
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
        
        if (BobbyConfig.enablePerformanceMonitoring) {
            perfMonitor.recordChunkLoaded();
        }

        world.markBlockRangeForRenderUpdate(x << 4, 0, z << 4, (x << 4) + 15, 256, (z << 4) + 15);
    }

    public boolean unload(int x, int z, boolean willBeReplaced) {
        cancelLoad(x, z);
        long pos = ChunkPos.asLong(x, z);
        Chunk chunk = fakeChunks.remove(pos);
        
        if (chunk != null) {
            if (BobbyConfig.enablePerformanceMonitoring) {
                perfMonitor.recordChunkUnloaded();
            }
            
            // Clean up chunk resources
            try {
                chunk.onUnload();
                world.loadedTileEntityList.removeAll(chunk.getTileEntityMap().values());
                world.tickableTileEntities.removeAll(chunk.getTileEntityMap().values());
            } catch (Exception e) {
                Bobby.LOGGER.error("Error unloading chunk at {}, {}", x, z, e);
            }

            return true;
        }

        return false;
    }

    private void cancelLoad(int x, int z) {
        long chunkPos = ChunkPos.asLong(x, z);
        chunksInLoadQueue.remove(chunkPos);
        LoadingJob job = loadingJobs.remove(chunkPos);
        if (job != null) {
            job.cancelled = true;
        }
    }
    
    public void scheduleLoadAfterUnload(int x, int z, NBTTagCompound tag, FakeChunkStorage storage) {
        long pos = ChunkPos.asLong(x, z);
        scheduledLoads.put(pos, new ScheduledLoad(x, z, tag, storage));
    }

    private static String getCurrentWorldOrServerName() {
        IntegratedServer integratedServer = client.getIntegratedServer();
        if (integratedServer != null) {
            return sanitizeFileName(integratedServer.getWorldName());
        }

        ServerData serverInfo = client.getCurrentServerData();
        if (serverInfo != null) {
            return sanitizeFileName(serverInfo.serverIP.replace(':', '_'));
        }

        if (client.isConnectedToRealms()) {
            return "realms";
        }

        return "unknown";
    }
    
    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public String getDebugString() {
        int queueSize = loadExecutor instanceof ThreadPoolExecutor ? 
            ((ThreadPoolExecutor)loadExecutor).getQueue().size() : 0;
        return String.format("F: %d L: %d U: %d Q: %d C: %d", 
            fakeChunks.size(), 
            loadingJobs.size(), 
            toBeUnloaded.size(),
            queueSize,
            completedJobs.size());
    }
    
    public void shutdown() {
        Bobby.LOGGER.info("Shutting down chunk manager for dimension {}...", world.provider.getDimension());
        
        // Cancel all pending loads
        for (LoadingJob job : loadingJobs.values()) {
            job.cancelled = true;
        }
        loadingJobs.clear();
        completedJobs.clear();
        
        // Flush any pending saves
        try {
            storage.flushPendingSaves();
        } catch (Exception e) {
            Bobby.LOGGER.error("Error flushing storage during shutdown", e);
        }
        
        // Shutdown this instance's executors
        loadExecutor.shutdown();
        saveExecutor.shutdown();
        
        try {
            if (!loadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loadExecutor.shutdownNow();
            }
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            loadExecutor.shutdownNow();
            saveExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Bobby.LOGGER.info("Chunk manager shutdown complete");
    }

    private static class UnloadTask {
        final long chunkPos;
        final long unloadTime;

        UnloadTask(long chunkPos, long unloadTime) {
            this.chunkPos = chunkPos;
            this.unloadTime = unloadTime;
        }

        long getUnloadTime() {
            return unloadTime;
        }
    }
    
    private static class ChunkLoadTask {
        final long chunkPos;
        final int distanceSquared;
        
        ChunkLoadTask(long chunkPos, int distanceSquared) {
            this.chunkPos = chunkPos;
            this.distanceSquared = distanceSquared;
        }
    }
    
    private static class ScheduledLoad {
        final int x, z;
        final NBTTagCompound tag;
        final FakeChunkStorage storage;
        
        ScheduledLoad(int x, int z, NBTTagCompound tag, FakeChunkStorage storage) {
            this.x = x;
            this.z = z;
            this.tag = tag;
            this.storage = storage;
        }
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private final int priority;
        private volatile boolean cancelled;
        private volatile Optional<Supplier<Chunk>> result;

        public LoadingJob(int x, int z, int priority) {
            this.x = x;
            this.z = z;
            this.priority = priority;
        }

        @Override
        public void run() {
            if (cancelled) {
                cleanup();
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
                
                if (!cancelled) {
                    completedJobs.offer(this);
                } else {
                    cleanup();
                }
            } catch (Exception e) {
                Bobby.LOGGER.error("Error in loading job for chunk {}, {}", x, z, e);
                result = Optional.empty();
                cleanup();
            }
        }

        public void complete() {
            if (result != null) {
                result.ifPresent(supplier -> {
                    if (!cancelled) {
                        try {
                            load(x, z, supplier.get());
                        } catch (Exception e) {
                            Bobby.LOGGER.error("Error completing load for chunk {}, {}", x, z, e);
                        }
                    }
                });
            }
            cleanup();
        }
        
        private void cleanup() {
            long chunkPos = ChunkPos.asLong(x, z);
            loadingJobs.remove(chunkPos);
            chunksInLoadQueue.remove(chunkPos);
        }
    }
}

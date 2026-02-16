package com.michaelsebero.bobby;

import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ChunkManager {
    private final WorldClient world;
    private final ChunkStorage storage;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final ForkJoinPool parallelPool;
    
    private final Long2ObjectMap<FakeChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final LongSet visitedChunks = LongSets.synchronize(new LongOpenHashSet());
    private final LongSet knownChunks = LongSets.synchronize(new LongOpenHashSet());
    
    private final Map<Long, NBTTagCompound> cache = Collections.synchronizedMap(
        new LinkedHashMap<Long, NBTTagCompound>(BobbyConfig.cacheSize, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<Long, NBTTagCompound> eldest) {
                return size() > BobbyConfig.cacheSize;
            }
        }
    );
    
    private final ConcurrentHashMap<Long, CompletableFuture<?>> loading = new ConcurrentHashMap<>();
    private final Queue<ChunkPos> loadQueue = new ConcurrentLinkedQueue<>();
    private final Queue<ChunkPos> unloadQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkPos> pendingRenderUpdates = ConcurrentHashMap.newKeySet();
    
    private int playerChunkX, playerChunkZ;
    private int lastUpdateX = Integer.MAX_VALUE, lastUpdateZ = Integer.MAX_VALUE;
    private int tickCounter = 0;
    private boolean restorationComplete = false;
    
    private final AtomicInteger totalLoaded = new AtomicInteger(0);
    private final AtomicInteger failedLoads = new AtomicInteger(0);

    public ChunkManager(WorldClient world, ChunkStorage storage) {
        this.world = world;
        this.storage = storage;
        
        int threads = Math.max(4, Math.min(BobbyConfig.loadThreads * 2, 
            Runtime.getRuntime().availableProcessors()));
        
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Bobby-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        
        this.parallelPool = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            true
        );
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Bobby-Saver");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.save();
            } catch (Exception e) {
                Bobby.LOGGER.debug("Auto-save failed", e);
            }
        }, BobbyConfig.saveInterval, BobbyConfig.saveInterval, TimeUnit.SECONDS);
        
        discoverKnownChunks();
    }
    
    private void discoverKnownChunks() {
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                Set<ChunkPos> storedChunks = storage.getStoredChunks();
                
                // Parallel stream for faster processing
                storedChunks.parallelStream().forEach(pos -> 
                    knownChunks.add(ChunkPos.asLong(pos.x, pos.z))
                );
                
                long elapsed = System.currentTimeMillis() - startTime;
                Bobby.LOGGER.info("Discovered " + storedChunks.size() + " cached chunks in " + elapsed + "ms");
            } catch (Exception e) {
                Bobby.LOGGER.error("Failed to discover cached chunks", e);
            }
        }, executor);
    }

    public void tick() {
        tickCounter++;
        
        if (Minecraft.getMinecraft().player != null) {
            playerChunkX = Minecraft.getMinecraft().player.chunkCoordX;
            playerChunkZ = Minecraft.getMinecraft().player.chunkCoordZ;
            
            // One-time restoration after world loads - use parallel loading
            if (!restorationComplete && tickCounter == 40) {
                restorationComplete = true;
                restoreCachedChunksAroundPlayerParallel();
            }
            
            // Regular updates
            if (tickCounter > 40 && shouldUpdate()) {
                lastUpdateX = playerChunkX;
                lastUpdateZ = playerChunkZ;
                updateChunks();
            }
        }
        
        processQueues();
        
        if (tickCounter % 3 == 0) {
            flushRenderUpdates();
        }
    }
    
    private boolean shouldUpdate() {
        if (tickCounter % 10 == 0) {
            return true;
        }
        
        int dx = playerChunkX - lastUpdateX;
        int dz = playerChunkZ - lastUpdateZ;
        return (dx * dx + dz * dz) >= 4;
    }
    
    /**
     * Parallel restoration of cached chunks for much faster initial loading
     */
    private void restoreCachedChunksAroundPlayerParallel() {
        if (knownChunks.isEmpty()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                int renderDist = BobbyConfig.renderDistance;
                int simDist = BobbyConfig.simulationDistance;
                
                // Convert LongSet to List for parallel processing
                List<Long> knownList = new ArrayList<>();
                synchronized (knownChunks) {
                    LongIterator iterator = knownChunks.iterator();
                    while (iterator.hasNext()) {
                        knownList.add(iterator.nextLong());
                    }
                }
                
                // Parallel filtering and sorting
                List<ChunkPos> toLoad = parallelPool.submit(() ->
                    knownList.parallelStream()
                        .map(pos -> new ChunkPos((int)(long)pos, (int)((long)pos >> 32)))
                        .filter(pos -> shouldLoadChunk(pos.x, pos.z, renderDist, simDist))
                        .filter(pos -> !fakeChunks.containsKey(ChunkPos.asLong(pos.x, pos.z)))
                        .sorted(Comparator.comparingInt(this::getDistanceSq))
                        .collect(Collectors.toList())
                ).join();
                
                if (!toLoad.isEmpty()) {
                    // Batch preload NBT data in parallel for better cache warming
                    int batchSize = Math.min(100, toLoad.size());
                    List<ChunkPos> priorityBatch = toLoad.subList(0, Math.min(batchSize, toLoad.size()));
                    
                    parallelPool.submit(() ->
                        priorityBatch.parallelStream().forEach(pos -> {
                            try {
                                long packed = ChunkPos.asLong(pos.x, pos.z);
                                if (!cache.containsKey(packed)) {
                                    NBTTagCompound nbt = storage.load(pos);
                                    if (nbt != null) {
                                        cache.put(packed, nbt);
                                    }
                                }
                            } catch (Exception e) {
                                Bobby.LOGGER.debug("Failed to preload chunk " + pos, e);
                            }
                        })
                    ).join();
                    
                    loadQueue.addAll(toLoad);
                    
                    long elapsed = System.currentTimeMillis() - startTime;
                    Bobby.LOGGER.info("Queued " + toLoad.size() + " chunks for restoration (preloaded " + 
                        priorityBatch.size() + " in cache) - took " + elapsed + "ms");
                }
            } catch (Exception e) {
                Bobby.LOGGER.error("Failed to restore cached chunks", e);
            }
        }, executor);
    }

    private void updateChunks() {
        int renderDist = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks, 
            BobbyConfig.renderDistance);
        int simDist = BobbyConfig.simulationDistance;
        
        // Queue unload for chunks too far away
        LongSet toUnload = new LongOpenHashSet();
        synchronized (fakeChunks) {
            for (long pos : fakeChunks.keySet()) {
                int x = (int) pos;
                int z = (int) (pos >> 32);
                
                if (!shouldLoadChunk(x, z, renderDist, simDist)) {
                    toUnload.add(pos);
                }
            }
        }
        
        for (long pos : toUnload) {
            unloadQueue.offer(new ChunkPos((int) pos, (int) (pos >> 32)));
        }
        
        // Queue load for nearby chunks - use parallel scanning for larger areas
        int scanRadius = Math.min(renderDist, 32);
        int scanArea = (scanRadius * 2 + 1) * (scanRadius * 2 + 1);
        
        List<ChunkPos> toLoad = new ArrayList<>();
        
        // Use parallel stream for larger scan areas
        if (scanArea > 400) {
            List<ChunkPos> candidates = parallelPool.submit(() ->
                java.util.stream.IntStream.range(-scanRadius, scanRadius + 1).parallel()
                    .boxed()
                    .flatMap(dx -> java.util.stream.IntStream.range(-scanRadius, scanRadius + 1)
                        .mapToObj(dz -> {
                            int x = playerChunkX + dx;
                            int z = playerChunkZ + dz;
                            long pos = ChunkPos.asLong(x, z);
                            
                            if (shouldLoadChunk(x, z, renderDist, simDist) && 
                                (visitedChunks.contains(pos) || knownChunks.contains(pos)) &&
                                !fakeChunks.containsKey(pos) && 
                                !loading.containsKey(pos)) {
                                return new ChunkPos(x, z);
                            }
                            return null;
                        })
                    )
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            ).join();
            
            candidates.sort(Comparator.comparingInt(this::getDistanceSq));
            toLoad = candidates.stream().limit(50).collect(Collectors.toList());
        } else {
            // Sequential for small areas
            for (int dx = -scanRadius; dx <= scanRadius; dx++) {
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    int x = playerChunkX + dx;
                    int z = playerChunkZ + dz;
                    long pos = ChunkPos.asLong(x, z);
                    
                    if (shouldLoadChunk(x, z, renderDist, simDist) && 
                        (visitedChunks.contains(pos) || knownChunks.contains(pos)) &&
                        !fakeChunks.containsKey(pos) && 
                        !loading.containsKey(pos)) {
                        toLoad.add(new ChunkPos(x, z));
                    }
                }
            }
            toLoad.sort(Comparator.comparingInt(this::getDistanceSq));
            toLoad = toLoad.stream().limit(50).collect(Collectors.toList());
        }
        
        loadQueue.addAll(toLoad);
    }
    
    private boolean shouldLoadChunk(int x, int z, int renderDist, int simDist) {
        int distSq = getDistanceSq(x, z);
        return distSq <= renderDist * renderDist && distSq > simDist * simDist;
    }
    
    private int getDistanceSq(int x, int z) {
        int dx = x - playerChunkX;
        int dz = z - playerChunkZ;
        return dx * dx + dz * dz;
    }
    
    private int getDistanceSq(ChunkPos pos) {
        return getDistanceSq(pos.x, pos.z);
    }

    private void processQueues() {
        int maxConcurrent = Math.max(16, BobbyConfig.loadThreads * 6);
        int maxStarts = BobbyConfig.chunksPerTick * 8;
        
        // Batch process multiple chunks at once for efficiency
        List<ChunkPos> batchToLoad = new ArrayList<>();
        int started = 0;
        
        while (loading.size() < maxConcurrent && started < maxStarts && !loadQueue.isEmpty()) {
            ChunkPos pos = loadQueue.poll();
            if (pos != null) {
                long packed = ChunkPos.asLong(pos.x, pos.z);
                if (!loading.containsKey(packed) && !fakeChunks.containsKey(packed)) {
                    batchToLoad.add(pos);
                    started++;
                }
            }
        }
        
        // Start all loads in the batch
        for (ChunkPos pos : batchToLoad) {
            loadAsync(pos.x, pos.z);
        }
        
        // Unload chunks
        int maxUnload = BobbyConfig.chunksPerTick * 6;
        for (int i = 0; i < maxUnload && !unloadQueue.isEmpty(); i++) {
            ChunkPos pos = unloadQueue.poll();
            if (pos != null) {
                unload(pos.x, pos.z);
            }
        }
    }

    private void loadAsync(int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
            try {
                if (world.getChunkProvider().getLoadedChunk(x, z) != null) {
                    return null;
                }
                
                NBTTagCompound nbt = cache.get(pos);
                if (nbt == null) {
                    nbt = storage.load(new ChunkPos(x, z));
                    if (nbt != null) {
                        cache.put(pos, nbt);
                    } else {
                        knownChunks.remove(pos);
                        failedLoads.incrementAndGet();
                        return null;
                    }
                }
                
                Chunk sourceChunk = storage.deserialize(new ChunkPos(x, z), nbt, world);
                if (sourceChunk != null) {
                    FakeChunk fake = new FakeChunk(world, x, z);
                    fake.copyFrom(sourceChunk);
                    return fake;
                }
                
                failedLoads.incrementAndGet();
                return null;
            } catch (Exception e) {
                Bobby.LOGGER.debug("Failed to load chunk at " + x + ", " + z, e);
                failedLoads.incrementAndGet();
                return null;
            }
        }, executor).thenAccept(fake -> {
            if (fake != null) {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    fakeChunks.put(pos, fake);
                    pendingRenderUpdates.add(new ChunkPos(x, z));
                    totalLoaded.incrementAndGet();
                });
            }
        }).whenComplete((v, ex) -> {
            loading.remove(pos);
            if (ex != null) {
                Bobby.LOGGER.debug("Chunk loading error at " + x + ", " + z, ex);
                failedLoads.incrementAndGet();
            }
        });
        
        loading.put(pos, future);
    }
    
    private void flushRenderUpdates() {
        if (pendingRenderUpdates.isEmpty()) {
            return;
        }
        
        Set<ChunkPos> updates = new HashSet<>(pendingRenderUpdates);
        pendingRenderUpdates.clear();
        
        if (!updates.isEmpty()) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                for (ChunkPos pos : updates) {
                    world.markBlockRangeForRenderUpdate(
                        pos.x << 4, 0, pos.z << 4, 
                        (pos.x << 4) + 15, 256, (pos.z << 4) + 15
                    );
                }
            });
        }
    }

    public void load(int x, int z, Chunk chunk) {
        long pos = ChunkPos.asLong(x, z);
        visitedChunks.add(pos);
        knownChunks.add(pos);
        
        executor.execute(() -> {
            try {
                NBTTagCompound nbt = storage.serialize(chunk);
                if (nbt != null) {
                    cache.put(pos, nbt);
                    storage.saveAsync(new ChunkPos(x, z), nbt);
                }
            } catch (Exception e) {
                Bobby.LOGGER.debug("Failed to cache chunk at " + x + ", " + z, e);
            }
        });
    }

    public void unload(int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        
        CompletableFuture<?> future = loading.remove(pos);
        if (future != null) {
            future.cancel(true);
        }
        
        FakeChunk fake = fakeChunks.remove(pos);
        
        if (fake != null) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                world.markBlockRangeForRenderUpdate(
                    x << 4, 0, z << 4, 
                    (x << 4) + 15, 256, (z << 4) + 15
                );
            });
        }
    }

    @Nullable
    public Chunk get(int x, int z) {
        return fakeChunks.get(ChunkPos.asLong(x, z));
    }

    public void shutdown() {
        loading.values().forEach(f -> f.cancel(true));
        loading.clear();
        
        scheduler.shutdownNow();
        parallelPool.shutdownNow();
        
        try {
            int pending = storage.getPendingCount();
            if (pending > 0) {
                Bobby.LOGGER.info("Flushing " + pending + " pending chunk saves...");
                storage.flush();
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to flush pending saves", e);
        }
        
        executor.shutdownNow();
        Bobby.LOGGER.info("Bobby shutdown complete");
    }

    public String getDebugInfo() {
        return String.format("Bobby - Fake: %d | Cache: %d | Queue: %d+%d | Known: %d | Loaded: %d | Failed: %d", 
            fakeChunks.size(), cache.size(), loadQueue.size(), unloadQueue.size(), 
            knownChunks.size(), totalLoaded.get(), failedLoads.get());
    }
}

package com.michaelsebero.bobby;

import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages fake chunk loading, caching, and lifecycle
 * PERFORMANCE: Fake chunks are visual snapshots only - never tick, never update
 */
public class ChunkManager {
    private final WorldClient world;
    private final ChunkStorage storage;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    
    // Fake chunks are separate from active chunks - they never enter World's chunk lists
    private final Long2ObjectMap<FakeChunk> fakeChunks = new Long2ObjectOpenHashMap<>();
    private final LongSet visitedChunks = new LongOpenHashSet();
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
    
    private int playerChunkX, playerChunkZ;
    private int lastUpdateX = Integer.MAX_VALUE, lastUpdateZ = Integer.MAX_VALUE;
    private int tickCounter = 0;
    
    // Batch render updates to reduce main thread overhead
    private final Set<ChunkPos> pendingRenderUpdates = Collections.synchronizedSet(new HashSet<>());
    
    // Track all chunks that exist in storage for restoration
    private final LongSet knownChunks = new LongOpenHashSet();
    private volatile boolean discoveryComplete = false;
    private volatile boolean restorationExecuted = false;

    public ChunkManager(WorldClient world, ChunkStorage storage) {
        this.world = world;
        this.storage = storage;
        
        int threads = Math.max(1, Math.min(BobbyConfig.loadThreads, 
            Runtime.getRuntime().availableProcessors() / 2));
        
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Bobby-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Bobby-Saver");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        // Auto-save on background thread
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.save();
            } catch (Exception e) {
                Bobby.LOGGER.error("Auto-save failed", e);
            }
        }, BobbyConfig.saveInterval, BobbyConfig.saveInterval, TimeUnit.SECONDS);
        
        // Discover all cached chunks on startup - this MUST complete before restoration
        Bobby.LOGGER.info("Starting synchronous chunk discovery...");
        discoverKnownChunks();
        discoveryComplete = true;
        Bobby.LOGGER.info("Chunk discovery completed, found {} cached chunks", knownChunks.size());
    }
    
    /**
     * Discover all chunks that exist in storage
     * This allows us to restore the cached area on world load
     * CRITICAL: This now runs SYNCHRONOUSLY to ensure it completes before any restoration
     */
    private void discoverKnownChunks() {
        try {
            Set<ChunkPos> storedChunks = storage.getStoredChunks();
            
            if (storedChunks.isEmpty()) {
                Bobby.LOGGER.info("No cached chunks found in storage");
                return;
            }
            
            for (ChunkPos pos : storedChunks) {
                knownChunks.add(pack(pos.x, pos.z));
            }
            
            Bobby.LOGGER.info("Successfully discovered {} cached chunks", storedChunks.size());
        } catch (Exception e) {
            Bobby.LOGGER.error("Error discovering cached chunks", e);
        }
    }

    public void tick() {
        tickCounter++;
        
        if (world.isRemote && Minecraft.getMinecraft().player != null) {
            int px = Minecraft.getMinecraft().player.chunkCoordX;
            int pz = Minecraft.getMinecraft().player.chunkCoordZ;
            
            playerChunkX = px;
            playerChunkZ = pz;
            
            // Execute restoration ONCE, 2 seconds after world load
            if (!restorationExecuted && discoveryComplete && tickCounter == 40) {
                restorationExecuted = true;
                Bobby.LOGGER.info("Executing restoration at tick {}, player at ({}, {})", tickCounter, px, pz);
                restoreCachedChunksAroundPlayer();
            }
            
            // Regular chunk updates for movement
            if (tickCounter > 40) { // Only after restoration
                int dx = px - lastUpdateX;
                int dz = pz - lastUpdateZ;
                
                // Full update every 20 ticks or when moved 2+ chunks
                if (tickCounter % 20 == 0 || dx * dx + dz * dz >= 4) {
                    lastUpdateX = px;
                    lastUpdateZ = pz;
                    updateChunks();
                }
            }
        }
        
        processQueues();
        
        // Batch render updates every 5 ticks
        if (tickCounter % 5 == 0 && !pendingRenderUpdates.isEmpty()) {
            flushRenderUpdates();
        }
    }
    
    /**
     * Restore all cached chunks around the player on world load
     * This simulates the player having "visited" all previously explored areas
     */
    private void restoreCachedChunksAroundPlayer() {
        if (knownChunks.isEmpty()) {
            Bobby.LOGGER.info("No cached chunks to restore - this may be first launch or new world");
            return;
        }
        
        // Use Bobby's configured render distance
        int renderDist = BobbyConfig.renderDistance;
        int simDist = BobbyConfig.simulationDistance;
        
        Bobby.LOGGER.info("=== RESTORATION START ===");
        Bobby.LOGGER.info("Player position: chunk ({}, {})", playerChunkX, playerChunkZ);
        Bobby.LOGGER.info("Render distance: {}, Simulation distance: {}", renderDist, simDist);
        Bobby.LOGGER.info("Total known chunks: {}", knownChunks.size());
        
        List<ChunkPos> toLoad = new ArrayList<>();
        
        // Statistics for debugging
        int totalKnown = knownChunks.size();
        int withinRange = 0;
        int withinSim = 0;
        int alreadyFake = 0;
        int queued = 0;
        
        // Track some example chunks for debugging
        List<String> simDistExamples = new ArrayList<>();
        List<String> queuedExamples = new ArrayList<>();
        
        for (long pos : knownChunks) {
            int x = (int) pos;
            int z = (int) (pos >> 32);
            
            // Calculate distance from player
            int dx = x - playerChunkX;
            int dz = z - playerChunkZ;
            int distSq = dx * dx + dz * dz;
            double actualDist = Math.sqrt(distSq);
            
            // Only load chunks within Bobby's extended render distance
            if (distSq > renderDist * renderDist) continue;
            withinRange++;
            
            // Skip simulation distance - server/world loads these normally
            // Use squared distance for consistent comparison
            if (distSq <= simDist * simDist) {
                withinSim++;
                if (simDistExamples.size() < 5) {
                    simDistExamples.add(String.format("(%d,%d) dist=%.1f", x, z, actualDist));
                }
                continue;
            }
            
            // Check if already loaded as fake
            if (fakeChunks.containsKey(pos)) {
                alreadyFake++;
                continue;
            }
            
            toLoad.add(new ChunkPos(x, z));
            queued++;
            if (queuedExamples.size() < 5) {
                queuedExamples.add(String.format("(%d,%d) dist=%.1f", x, z, actualDist));
            }
        }
        
        Bobby.LOGGER.info("Statistics:");
        Bobby.LOGGER.info("  Total cached chunks: {}", totalKnown);
        Bobby.LOGGER.info("  Within render distance: {}", withinRange);
        Bobby.LOGGER.info("  Within simulation distance (skipped): {}", withinSim);
        Bobby.LOGGER.info("  Already loaded as fake: {}", alreadyFake);
        Bobby.LOGGER.info("  Queued for loading: {}", queued);
        
        if (!queuedExamples.isEmpty()) {
            Bobby.LOGGER.info("  Example queued chunks: {}", queuedExamples);
        }
        
        if (toLoad.isEmpty()) {
            Bobby.LOGGER.info("No chunks queued for restoration - may need to explore more first");
            return;
        }
        
        // Prioritize closest chunks first for better visual experience
        toLoad.sort(Comparator.comparingInt(p -> {
            int dx = p.x - playerChunkX;
            int dz = p.z - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        
        // Queue all loads
        int maxQueue = Math.min(toLoad.size(), 10000);
        for (int i = 0; i < maxQueue; i++) {
            loadQueue.offer(toLoad.get(i));
        }
        
        Bobby.LOGGER.info("Queued {} chunks for restoration (closest first)", maxQueue);
        Bobby.LOGGER.info("=== RESTORATION END ===");
    }

    private void updateChunks() {
        int renderDist = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks, 
            BobbyConfig.renderDistance);
        int simDist = BobbyConfig.simulationDistance;
        int renderDistSq = renderDist * renderDist;
        
        // Unload out-of-range fake chunks
        LongSet toUnload = new LongOpenHashSet();
        
        synchronized (fakeChunks) {
            for (Long2ObjectMap.Entry<FakeChunk> entry : fakeChunks.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                int x = (int) pos;
                int z = (int) (pos >> 32);
                int dx = x - playerChunkX;
                int dz = z - playerChunkZ;
                
                if (dx * dx + dz * dz > renderDistSq) {
                    toUnload.add(pos);
                }
            }
        }
        
        for (long pos : toUnload) {
            unloadQueue.offer(new ChunkPos((int) pos, (int) (pos >> 32)));
        }
        
        // Load chunks OUTSIDE simulation distance
        List<ChunkPos> toLoad = new ArrayList<>();
        int scanRadius = Math.min(renderDist, 24);
        
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > renderDistSq) continue;
                
                // CRITICAL: Only load fake chunks OUTSIDE simulation distance
                // Use squared distance for consistent comparison
                if (distSq <= simDist * simDist) continue;
                
                int x = playerChunkX + dx;
                int z = playerChunkZ + dz;
                long pos = pack(x, z);
                
                // Load if chunk is in knownChunks OR visitedChunks
                if ((visitedChunks.contains(pos) || knownChunks.contains(pos)) && 
                    !fakeChunks.containsKey(pos) && 
                    !loading.containsKey(pos)) {
                    toLoad.add(new ChunkPos(x, z));
                }
            }
        }
        
        // Prioritize closest chunks
        toLoad.sort(Comparator.comparingInt(p -> {
            int dx = p.x - playerChunkX;
            int dz = p.z - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        
        // Queue up to 5x chunksPerTick for smooth loading
        toLoad.stream().limit(BobbyConfig.chunksPerTick * 5).forEach(loadQueue::offer);
    }

    private void processQueues() {
        // Load new fake chunks (off main thread)
        int loaded = 0;
        while (loaded < BobbyConfig.chunksPerTick && !loadQueue.isEmpty()) {
            ChunkPos pos = loadQueue.poll();
            if (pos != null && !loading.containsKey(pack(pos.x, pos.z))) {
                loadAsync(pos.x, pos.z);
                loaded++;
            }
        }
        
        // Unload old fake chunks (fast)
        int unloaded = 0;
        while (unloaded < BobbyConfig.chunksPerTick * 2 && !unloadQueue.isEmpty()) {
            ChunkPos pos = unloadQueue.poll();
            if (pos != null) {
                unload(pos.x, pos.z);
                unloaded++;
            }
        }
        
        // Log progress every 5 seconds
        if (tickCounter % 100 == 0 && !loadQueue.isEmpty()) {
            Bobby.LOGGER.info("Loading progress - Queue: {}, Loaded: {}, Loading: {}", 
                loadQueue.size(), fakeChunks.size(), loading.size());
        }
    }

    private void loadAsync(int x, int z) {
        long pos = pack(x, z);
        
        Bobby.LOGGER.debug("Starting async load for chunk ({}, {})", x, z);
        
        // All work happens on background threads
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
            try {
                Bobby.LOGGER.debug("Worker thread processing chunk ({}, {})", x, z);
                
                // CRITICAL: Re-check if chunk is now loaded as real
                // This prevents loading fake chunks over real ones
                if (world.getChunkProvider().getLoadedChunk(x, z) != null) {
                    Bobby.LOGGER.debug("Chunk ({}, {}) is now loaded as real, skipping fake load", x, z);
                    return null;
                }
                
                // Load from cache or disk
                NBTTagCompound nbt = cache.get(pos);
                if (nbt == null) {
                    Bobby.LOGGER.debug("Chunk ({}, {}) not in cache, loading from disk", x, z);
                    nbt = storage.load(new ChunkPos(x, z));
                    if (nbt != null) {
                        cache.put(pos, nbt);
                        Bobby.LOGGER.debug("Chunk ({}, {}) loaded from disk and cached", x, z);
                    } else {
                        // Chunk doesn't actually exist on disk
                        Bobby.LOGGER.debug("Chunk ({}, {}) not found on disk", x, z);
                        knownChunks.remove(pos);
                        return null;
                    }
                }
                
                if (nbt != null) {
                    Bobby.LOGGER.debug("Deserializing chunk ({}, {})", x, z);
                    Chunk sourceChunk = storage.deserialize(new ChunkPos(x, z), nbt, world);
                    if (sourceChunk != null) {
                        // Create frozen snapshot
                        Bobby.LOGGER.debug("Creating FakeChunk for ({}, {})", x, z);
                        FakeChunk fake = new FakeChunk(world, x, z);
                        fake.copyFrom(sourceChunk);
                        
                        Bobby.LOGGER.debug("Successfully created fake chunk ({}, {})", x, z);
                        return fake;
                    } else {
                        Bobby.LOGGER.warn("Failed to deserialize chunk ({}, {})", x, z);
                    }
                }
                return null;
            } catch (Exception e) {
                Bobby.LOGGER.error("Exception loading chunk ({}, {})", x, z, e);
                return null;
            }
        }, executor).thenAccept(fake -> {
            Bobby.LOGGER.debug("Main thread callback for chunk ({}, {}), fake={}", x, z, fake != null);
            if (fake != null) {
                // Schedule on main thread for thread safety
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    Bobby.LOGGER.debug("Registering fake chunk ({}, {}) on main thread", x, z);
                    synchronized (fakeChunks) {
                        fakeChunks.put(pos, fake);
                    }
                    
                    // Queue render update (batched later)
                    pendingRenderUpdates.add(new ChunkPos(x, z));
                    
                    Bobby.LOGGER.info("Registered fake chunk ({}, {}), total: {}", x, z, fakeChunks.size());
                    
                    if (fakeChunks.size() % 10 == 0) {
                        Bobby.LOGGER.info("Loaded {} fake chunks", fakeChunks.size());
                    }
                });
            }
        }).whenComplete((v, ex) -> {
            Bobby.LOGGER.debug("Completing load for chunk ({}, {})", x, z);
            loading.remove(pos);
            if (ex != null) {
                Bobby.LOGGER.error("Failed to load chunk ({}, {})", x, z, ex);
            }
        });
        
        loading.put(pos, future);
        Bobby.LOGGER.debug("Queued async load for chunk ({}, {})", x, z);
    }
    
    private void flushRenderUpdates() {
        Set<ChunkPos> updates;
        synchronized (pendingRenderUpdates) {
            updates = new HashSet<>(pendingRenderUpdates);
            pendingRenderUpdates.clear();
        }
        
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

    /**
     * Save a real chunk as a snapshot for later fake loading
     */
    public void load(int x, int z, Chunk chunk) {
        long pos = pack(x, z);
        visitedChunks.add(pos);
        knownChunks.add(pos);
        
        Bobby.LOGGER.debug("Saving snapshot for chunk ({}, {})", x, z);
        
        // Save snapshot asynchronously - never block main thread
        executor.execute(() -> {
            try {
                NBTTagCompound nbt = storage.serialize(chunk);
                if (nbt != null) {
                    cache.put(pos, nbt);
                    storage.saveAsync(new ChunkPos(x, z), nbt);
                    Bobby.LOGGER.debug("Queued chunk ({}, {}) for async save", x, z);
                } else {
                    Bobby.LOGGER.warn("Failed to serialize chunk ({}, {})", x, z);
                }
            } catch (Exception e) {
                Bobby.LOGGER.error("Exception saving snapshot ({}, {})", x, z, e);
            }
        });
    }

    public void unload(int x, int z) {
        long pos = pack(x, z);
        
        // Cancel any pending load
        CompletableFuture<?> future = loading.remove(pos);
        if (future != null) {
            future.cancel(true);
        }
        
        // Remove from fake chunks (no cleanup needed - they have no active state)
        FakeChunk fake;
        synchronized (fakeChunks) {
            fake = fakeChunks.remove(pos);
        }
        
        // Fake chunks have no entities or tile entities, so unload is instant
        if (fake != null) {
            // Just mark for render update
            Minecraft.getMinecraft().addScheduledTask(() -> {
                world.markBlockRangeForRenderUpdate(
                    x << 4, 0, z << 4, 
                    (x << 4) + 15, 256, (z << 4) + 15
                );
            });
        }
    }

    /**
     * Get a fake chunk for rendering
     * Returns null if chunk should be handled by normal chunk provider
     */
    @Nullable
    public Chunk get(int x, int z) {
        synchronized (fakeChunks) {
            return fakeChunks.get(pack(x, z));
        }
    }

    public void shutdown() {
        Bobby.LOGGER.info("=== BOBBY SHUTDOWN ===");
        Bobby.LOGGER.info("Shutting down chunk manager - Fake: {}, Visited: {}", 
            fakeChunks.size(), visitedChunks.size());
        
        // Cancel any pending async loads
        loading.values().forEach(f -> f.cancel(true));
        loading.clear();
        
        // Stop the scheduler so no more auto-saves interfere
        scheduler.shutdownNow();
        
        // Flush ALL pending saves to disk
        try {
            Bobby.LOGGER.info("Flushing Bobby cache to disk...");
            int pendingCount = storage.getPendingCount();
            Bobby.LOGGER.info("Pending chunks to save: {}", pendingCount);
            
            if (pendingCount > 0) {
                storage.flush();
                Bobby.LOGGER.info("Successfully flushed {} chunks to disk", pendingCount);
            } else {
                Bobby.LOGGER.info("No pending chunks to flush");
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to flush storage", e);
        }
        
        // Shutdown worker threads last
        executor.shutdownNow();
        
        Bobby.LOGGER.info("=== BOBBY SHUTDOWN COMPLETE ===");
    }

    public String getDebugInfo() {
        return String.format("Bobby - Fake: %d | Cache: %d | Queue: %d | Known: %d", 
            fakeChunks.size(), cache.size(), loadQueue.size() + unloadQueue.size(), knownChunks.size());
    }

    private static long pack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}

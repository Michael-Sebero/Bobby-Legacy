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

public class ChunkManager {
    private final WorldClient world;
    private final ChunkStorage storage;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    
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
    
    private final Set<ChunkPos> pendingRenderUpdates = Collections.synchronizedSet(new HashSet<>());
    private final LongSet knownChunks = new LongOpenHashSet();
    private volatile boolean discoveryComplete = false;
    private volatile boolean restorationExecuted = false;
    private volatile boolean restorationInProgress = false;
    
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
                // Silent fail
            }
        }, BobbyConfig.saveInterval, BobbyConfig.saveInterval, TimeUnit.SECONDS);
        
        discoverKnownChunks();
        discoveryComplete = true;
    }
    
    private void discoverKnownChunks() {
        try {
            Set<ChunkPos> storedChunks = storage.getStoredChunks();
            
            if (storedChunks.isEmpty()) {
                return;
            }
            
            for (ChunkPos pos : storedChunks) {
                knownChunks.add(pack(pos.x, pos.z));
            }
        } catch (Exception e) {
            // Silent fail
        }
    }

    public void tick() {
        tickCounter++;
        
        if (world.isRemote && Minecraft.getMinecraft().player != null) {
            int px = Minecraft.getMinecraft().player.chunkCoordX;
            int pz = Minecraft.getMinecraft().player.chunkCoordZ;
            
            playerChunkX = px;
            playerChunkZ = pz;
            
            if (!restorationExecuted && discoveryComplete && tickCounter == 40) {
                restorationExecuted = true;
                restorationInProgress = true;
                restoreCachedChunksAroundPlayer();
            }
            
            if (tickCounter > 40) {
                int dx = px - lastUpdateX;
                int dz = pz - lastUpdateZ;
                
                if (tickCounter % 10 == 0 || dx * dx + dz * dz >= 4) {
                    lastUpdateX = px;
                    lastUpdateZ = pz;
                    updateChunks();
                }
            }
        }
        
        processQueues();
        
        if (tickCounter % 3 == 0 && !pendingRenderUpdates.isEmpty()) {
            flushRenderUpdates();
        }
    }
    
    private void restoreCachedChunksAroundPlayer() {
        if (knownChunks.isEmpty()) {
            restorationInProgress = false;
            return;
        }
        
        int renderDist = BobbyConfig.renderDistance;
        int simDist = BobbyConfig.simulationDistance;
        
        List<ChunkPos> toLoad = new ArrayList<>();
        int renderDistSq = renderDist * renderDist;
        int simDistSq = simDist * simDist;
        
        for (long pos : knownChunks) {
            int x = (int) pos;
            int z = (int) (pos >> 32);
            
            int dx = x - playerChunkX;
            int dz = z - playerChunkZ;
            int distSq = dx * dx + dz * dz;
            
            if (distSq > renderDistSq || distSq <= simDistSq) continue;
            if (fakeChunks.containsKey(pos)) continue;
            
            toLoad.add(new ChunkPos(x, z));
        }
        
        if (toLoad.isEmpty()) {
            restorationInProgress = false;
            return;
        }
        
        toLoad.sort(Comparator.comparingInt(p -> {
            int dx = p.x - playerChunkX;
            int dz = p.z - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        
        loadQueue.addAll(toLoad);
    }

    private void updateChunks() {
        int renderDist = Math.min(Minecraft.getMinecraft().gameSettings.renderDistanceChunks, 
            BobbyConfig.renderDistance);
        int simDist = BobbyConfig.simulationDistance;
        int renderDistSq = renderDist * renderDist;
        int simDistSq = simDist * simDist;
        
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
        
        List<ChunkPos> toLoad = new ArrayList<>();
        int scanRadius = Math.min(renderDist, 32);
        
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                int distSq = dx * dx + dz * dz;
                
                if (distSq > renderDistSq || distSq <= simDistSq) continue;
                
                int x = playerChunkX + dx;
                int z = playerChunkZ + dz;
                long pos = pack(x, z);
                
                if ((visitedChunks.contains(pos) || knownChunks.contains(pos)) && 
                    !fakeChunks.containsKey(pos) && 
                    !loading.containsKey(pos)) {
                    toLoad.add(new ChunkPos(x, z));
                }
            }
        }
        
        toLoad.sort(Comparator.comparingInt(p -> {
            int dx = p.x - playerChunkX;
            int dz = p.z - playerChunkZ;
            return dx * dx + dz * dz;
        }));
        
        int queueLimit = restorationInProgress ? 100 : 50;
        toLoad.stream().limit(queueLimit).forEach(loadQueue::offer);
    }

    private void processQueues() {
        int maxConcurrent = Math.max(16, BobbyConfig.loadThreads * 6);
        
        int started = 0;
        int maxStarts = BobbyConfig.chunksPerTick * 8;
        
        while (loading.size() < maxConcurrent && started < maxStarts && !loadQueue.isEmpty()) {
            ChunkPos pos = loadQueue.poll();
            if (pos == null) break;
            
            long packed = pack(pos.x, pos.z);
            if (!loading.containsKey(packed) && !fakeChunks.containsKey(packed)) {
                loadAsync(pos.x, pos.z);
                started++;
            }
        }
        
        int unloaded = 0;
        int maxUnload = BobbyConfig.chunksPerTick * 6;
        while (unloaded < maxUnload && !unloadQueue.isEmpty()) {
            ChunkPos pos = unloadQueue.poll();
            if (pos != null) {
                unload(pos.x, pos.z);
                unloaded++;
            }
        }
    }

    private void loadAsync(int x, int z) {
        long pos = pack(x, z);
        
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
                
                if (nbt != null) {
                    Chunk sourceChunk = storage.deserialize(new ChunkPos(x, z), nbt, world);
                    if (sourceChunk != null) {
                        FakeChunk fake = new FakeChunk(world, x, z);
                        fake.copyFrom(sourceChunk);
                        return fake;
                    } else {
                        failedLoads.incrementAndGet();
                    }
                }
                return null;
            } catch (Exception e) {
                failedLoads.incrementAndGet();
                return null;
            }
        }, executor).thenAccept(fake -> {
            if (fake != null) {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    synchronized (fakeChunks) {
                        fakeChunks.put(pos, fake);
                    }
                    pendingRenderUpdates.add(new ChunkPos(x, z));
                    totalLoaded.incrementAndGet();
                });
            }
        }).whenComplete((v, ex) -> {
            loading.remove(pos);
            if (ex != null) {
                failedLoads.incrementAndGet();
            }
        });
        
        loading.put(pos, future);
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

    public void load(int x, int z, Chunk chunk) {
        long pos = pack(x, z);
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
                // Silent fail
            }
        });
    }

    public void unload(int x, int z) {
        long pos = pack(x, z);
        
        CompletableFuture<?> future = loading.remove(pos);
        if (future != null) {
            future.cancel(true);
        }
        
        FakeChunk fake;
        synchronized (fakeChunks) {
            fake = fakeChunks.remove(pos);
        }
        
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
        synchronized (fakeChunks) {
            return fakeChunks.get(pack(x, z));
        }
    }

    public void shutdown() {
        loading.values().forEach(f -> f.cancel(true));
        loading.clear();
        
        scheduler.shutdownNow();
        
        try {
            int pending = storage.getPendingCount();
            if (pending > 0) {
                storage.flush();
            }
        } catch (Exception e) {
            // Silent fail
        }
        
        executor.shutdownNow();
    }

    public String getDebugInfo() {
        return String.format("Bobby - Fake: %d | Cache: %d | Queue: %d | Known: %d", 
            fakeChunks.size(), cache.size(), loadQueue.size() + unloadQueue.size(), knownChunks.size());
    }

    private static long pack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}

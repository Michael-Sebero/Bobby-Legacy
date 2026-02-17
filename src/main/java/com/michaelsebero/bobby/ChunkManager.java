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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * FIX (Bug 3a): Guard against double-shutdown.
     * Both MinecraftMixin.onShutdown and WorldClientMixin.sendQuittingDisconnectingPacket
     * call shutdown(). Without this flag, executor services receive shutdownNow() twice,
     * and flush() runs twice — the second run is a no-op but the double executor shutdown
     * logs confusing errors and can interfere with any still-running tasks.
     */
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

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

            if (!restorationComplete && tickCounter == 40) {
                restorationComplete = true;
                restoreCachedChunksAroundPlayerParallel();
            }

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

    private void restoreCachedChunksAroundPlayerParallel() {
        if (knownChunks.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                int renderDist = BobbyConfig.renderDistance;
                int simDist = BobbyConfig.simulationDistance;

                List<Long> knownList = new ArrayList<>();
                synchronized (knownChunks) {
                    LongIterator iterator = knownChunks.iterator();
                    while (iterator.hasNext()) {
                        knownList.add(iterator.nextLong());
                    }
                }

                List<ChunkPos> toLoad = parallelPool.submit(() ->
                    knownList.parallelStream()
                        .map(pos -> new ChunkPos((int)(long)pos, (int)((long)pos >> 32)))
                        .filter(pos -> shouldLoadChunk(pos.x, pos.z, renderDist, simDist))
                        .filter(pos -> !fakeChunks.containsKey(ChunkPos.asLong(pos.x, pos.z)))
                        .sorted(Comparator.comparingInt(this::getDistanceSq))
                        .collect(Collectors.toList())
                ).join();

                if (!toLoad.isEmpty()) {
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

        List<ChunkPos> toLoad = new ArrayList<>();

        for (int dx = -renderDist; dx <= renderDist; dx++) {
            for (int dz = -renderDist; dz <= renderDist; dz++) {
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

        loadQueue.addAll(toLoad);

        /**
         * FIX (Flicker Bug - part 2): Evict FakeChunks that have moved strictly outside
         * render distance. The eviction condition is ONLY distSq > renderDist*renderDist.
         *
         * Critically, we do NOT evict FakeChunks that are within simulationDistance.
         * Those positions are covered by real server chunks, so the FakeChunk is dormant
         * and harmless (provideChunk only substitutes when vanilla returns blankChunk).
         * Evicting them at the sim distance boundary caused a permanent removal: the chunk
         * left the Bobby zone, scheduleReload never fired (no server unload packet for
         * chunks the server still holds), and the FakeChunk was gone for good — producing
         * the "vanishes completely and doesn't come back" behavior.
         */
        List<Long> toEvict = new ArrayList<>();
        synchronized (fakeChunks) {
            LongIterator it = fakeChunks.keySet().iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                int cx = (int) packed;
                int cz = (int) (packed >> 32);
                int distSq = getDistanceSq(cx, cz);
                if (distSq > renderDist * renderDist) {
                    toEvict.add(packed);
                }
            }
        }
        for (long packed : toEvict) {
            int cx = (int) packed;
            int cz = (int) (packed >> 32);
            unload(cx, cz);
        }
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

        for (ChunkPos pos : batchToLoad) {
            loadAsync(pos.x, pos.z);
        }

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

        /**
         * FIX: The original code created the CompletableFuture first, then called
         * loading.put(pos, future) at the end. Since the future is already executing
         * as soon as supplyAsync() is called, it can complete and its whenComplete
         * handler can call loading.remove(pos) BEFORE loading.put(pos, future) runs.
         * This leaves a completed future permanently in the loading map, blocking any
         * future load attempts for that chunk position.
         *
         * Fix: Register a placeholder CompletableFuture in loading BEFORE submitting
         * any work to the executor. The placeholder is completed by whenComplete once
         * the task finishes, keeping loading.remove() consistent.
         */
        CompletableFuture<Void> placeholder = new CompletableFuture<>();

        // putIfAbsent ensures we don't stomp a concurrent entry from unload() cancellation.
        if (loading.putIfAbsent(pos, placeholder) != null) {
            // Another task is already loading this chunk.
            return;
        }

        CompletableFuture.<FakeChunk>supplyAsync(() -> {
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
            loading.remove(pos, placeholder); // only remove if still our placeholder
            placeholder.complete(null);
            if (ex != null) {
                Bobby.LOGGER.debug("Chunk loading error at " + x + ", " + z, ex);
                failedLoads.incrementAndGet();
            }
        });
    }

    private void flushRenderUpdates() {
        if (pendingRenderUpdates.isEmpty()) {
            return;
        }

        Set<ChunkPos> updates = new HashSet<>(pendingRenderUpdates);
        pendingRenderUpdates.clear();

        Minecraft.getMinecraft().addScheduledTask(() -> {
            for (ChunkPos pos : updates) {
                world.markBlockRangeForRenderUpdate(
                    pos.x << 4, 0, pos.z << 4,
                    (pos.x << 4) + 15, 256, (pos.z << 4) + 15
                );
            }
        });
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

    /**
     * Queues a (re)load for a chunk position without removing any existing FakeChunk.
     *
     * Called from ChunkProviderClientMixin.onUnloadChunk instead of the old unload() call.
     * When the server drops a real chunk, Bobby should immediately take over with the
     * cached FakeChunk (if one exists) or load a fresh one (if not). Either way the
     * existing FakeChunk must NOT be removed — that caused the blank-frame flicker gap.
     *
     * If a FakeChunk is already in the map for this position it will be returned by
     * provideChunk() with zero gap. If there is no FakeChunk yet, queuing a load here
     * means it appears as soon as deserialization completes. Out-of-range FakeChunk
     * eviction is handled exclusively by updateChunks().
     */
    public void scheduleReload(int x, int z) {
        long pos = ChunkPos.asLong(x, z);
        // Only queue a load if we have cached data and aren't already loading/loaded.
        if (!fakeChunks.containsKey(pos) && knownChunks.contains(pos) && !loading.containsKey(pos)) {
            loadQueue.add(new ChunkPos(x, z));
        }
    }

    @Nullable
    public Chunk get(int x, int z) {
        return fakeChunks.get(ChunkPos.asLong(x, z));
    }

    public void shutdown() {
        /**
         * FIX (Bug 3a): Guard against double-shutdown. Both MinecraftMixin.onShutdown and
         * WorldClientMixin.sendQuittingDisconnectingPacket call this method. Without the
         * guard, executor services get shutdownNow() called twice, causing logged errors
         * and potential interference with any tasks still running on the first call.
         */
        if (!isShutdown.compareAndSet(false, true)) {
            Bobby.LOGGER.debug("Bobby shutdown already in progress, skipping duplicate call");
            return;
        }

        loading.values().forEach(f -> f.cancel(true));
        loading.clear();

        // Stop the auto-save scheduler first so it doesn't interfere with the final flush.
        scheduler.shutdownNow();
        parallelPool.shutdownNow();

        /**
         * FIX (Bug 3b): Shut down the executor and wait for in-flight serialization to
         * finish BEFORE flushing. If we flush first and an executor task completes after,
         * its saveAsync() call adds entries to pending that are never written (silent data
         * loss).
         */
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                Bobby.LOGGER.warn("Executor did not terminate in time; some chunk data may be lost");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            int pending = storage.getPendingCount();
            if (pending > 0) {
                Bobby.LOGGER.info("Flushing " + pending + " pending chunk saves...");
                storage.flush();
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to flush pending saves", e);
        }

        Bobby.LOGGER.info("Bobby shutdown complete");
    }

    public String getDebugInfo() {
        return String.format("Bobby - Fake: %d | Cache: %d | Queue: %d+%d | Known: %d | Loaded: %d | Failed: %d",
            fakeChunks.size(), cache.size(), loadQueue.size(), unloadQueue.size(),
            knownChunks.size(), totalLoaded.get(), failedLoads.get());
    }
}

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

    private final ConcurrentHashMap<Long, AtomicBoolean> loading = new ConcurrentHashMap<>();
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
        /**
         * FIX (OptiFine chunk-caching dead zone): this used to clamp renderDist to
         * Math.min(gameSettings.renderDistanceChunks, BobbyConfig.renderDistance).
         * That ties Bobby's core load range to a live vanilla field that other code can
         * reset independently of Bobby's own config - see the matching fix in
         * GameSettingsMixin for why OptiFine in particular does this routinely (its
         * video settings screen and the vanilla F3+F hotkey both funnel through
         * GameSettings.setOptionValue, which re-clamps renderDistanceChunks to vanilla's
         * hardcoded max on every call, independent of what Bobby is configured for).
         *
         * When that live field collapses to at or below simulationDistance,
         * shouldLoadChunk() can never be satisfied for any position - the loop below
         * queues nothing, every position fails the check, and Bobby silently stops
         * caching entirely with no exception anywhere to log. BobbyConfig.renderDistance
         * is Bobby's own authoritative setting; restoreCachedChunksAroundPlayerParallel()
         * already uses it alone, unclamped. Do the same here.
         */
        int renderDist = BobbyConfig.renderDistance;
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
        /**
         * FIX (unthrottled eviction burst): this used to call unload(cx, cz) directly
         * here, for every position in toEvict, all in one synchronous pass with no cap -
         * unlike the load side just above, which caps toLoad at 50 per scan specifically
         * to avoid doing too much at once. Each unload() that finds a FakeChunk present
         * schedules a markBlockRangeForRenderUpdate call via addScheduledTask. A large
         * teleport, or dropping renderDistance while a lot of area is cached, could evict
         * thousands of positions in a single updateChunks() pass - which meant thousands
         * of render-update calls all bursting onto the very next tick at once.
         *
         * unloadQueue and its throttle in processQueues() (maxUnload = chunksPerTick * 6)
         * already existed for exactly this - they just were never fed. Queuing here
         * instead of unloading immediately spreads that same burst across multiple ticks.
         */
        for (long packed : toEvict) {
            int cx = (int) packed;
            int cz = (int) (packed >> 32);
            unloadQueue.add(new ChunkPos(cx, cz));
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
         * FIX (getLoadedChunk() called off the main thread): this used to check
         * world.getChunkProvider().getLoadedChunk(x, z) from inside the supplyAsync
         * block below, i.e. on a background executor thread. ChunkProviderClient's
         * internal chunk storage is only ever meant to be touched from the main
         * thread - vanilla mutates it there via provideChunk/loadChunk/unloadChunk
         * with no synchronization of its own, so reading it concurrently from a
         * worker thread is a data race (best case: a stale answer; worst case
         * depends on exactly what that storage is backed by internally, which isn't
         * something worth gambling on).
         *
         * Fix: only ever call getLoadedChunk() from the main thread. Checked here,
         * synchronously, before any work is dispatched (loadAsync() is only ever
         * called from the main thread, so this is safe) as a cheap early-out, and
         * checked again just before the FakeChunk is actually published (see the
         * addScheduledTask block below) since that's both safe and the freshest
         * possible point to ask the question.
         */
        if (world.getChunkProvider().getLoadedChunk(x, z) != null) {
            return;
        }

        /**
         * FIX (unload() cancellation was a no-op): the previous version put a bare
         * placeholder CompletableFuture in `loading` (to fix an earlier put/remove
         * ordering race - see below) and had unload() call .cancel(true) on it. That
         * placeholder was never actually linked to the real supplyAsync(...) chain
         * doing the work below - the only connection was whenComplete() manually
         * completing it at the end. Cancelling it did nothing: the real background
         * load kept running and could still fakeChunks.put(...) a chunk back in
         * shortly after unload() had already evicted it.
         *
         * Fix: loading now stores a single shared AtomicBoolean "cancelled" flag
         * instead of a disconnected future. unload()/shutdown() flip the same flag
         * this task checks - once at the start (skip entirely if already cancelled,
         * saving the disk read/deserialize), and again right before publishing to
         * fakeChunks (the check that actually matters: it guarantees a position that's
         * been evicted can never be resurrected by a load that was already in flight,
         * no matter how the two race).
         *
         * The original ordering fix is preserved: the flag is registered in `loading`
         * before any work is submitted to the executor, so there's still no window
         * where a fast-completing task could find its own entry missing.
         */
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // putIfAbsent ensures we don't stomp a concurrent entry from unload() cancellation.
        if (loading.putIfAbsent(pos, cancelled) != null) {
            // Another task is already loading this chunk.
            return;
        }

        CompletableFuture.<FakeChunk>supplyAsync(() -> {
            try {
                if (cancelled.get()) {
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
            if (fake != null && !cancelled.get()) {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    // Re-check cancellation on the main thread: unload() could have
                    // flipped the flag in the gap between this check and
                    // addScheduledTask actually running.
                    //
                    // Also re-check getLoadedChunk() here instead of in the
                    // background task above - this is the main thread, so it's safe,
                    // and it's the freshest point available before actually publishing.
                    if (cancelled.get() || world.getChunkProvider().getLoadedChunk(x, z) != null) {
                        return;
                    }
                    fakeChunks.put(pos, fake);
                    pendingRenderUpdates.add(new ChunkPos(x, z));
                    totalLoaded.incrementAndGet();
                });
            }
        }).whenComplete((v, ex) -> {
            loading.remove(pos, cancelled); // only remove if still our flag
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

        /**
         * FIX (stale FakeChunk after a real-chunk round-trip): a FakeChunk built from
         * older cached data can still be sitting in fakeChunks for this position (e.g.
         * it was shown while the server hadn't sent this chunk yet). Now that the real
         * chunk has loaded, that FakeChunk is superseded - remove it so that if this
         * position unloads again later, scheduleReload()'s "!fakeChunks.containsKey(pos)"
         * guard sees no stale entry and queues a fresh reload from the data cached below,
         * instead of silently resurrecting the old, now-outdated snapshot.
         */
        fakeChunks.remove(pos);

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

        AtomicBoolean cancelled = loading.remove(pos);
        if (cancelled != null) {
            // Signals the in-flight loadAsync() task (if any) to bail out at its next
            // check instead of resurrecting this position after we've just evicted it.
            cancelled.set(true);
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

    /** Main thread waits at most this long for the shutdown drain below to finish. */
    private static final long SHUTDOWN_DRAIN_BUDGET_MS = 3000;

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

        loading.values().forEach(flag -> flag.set(true));
        loading.clear();

        // Stop the auto-save scheduler first so it doesn't interfere with the final flush.
        scheduler.shutdownNow();
        parallelPool.shutdownNow();
        executor.shutdown();

        /**
         * FIX (main-thread stall on quit): waiting for the executor to terminate and then
         * flushing pending saves used to happen right here, synchronously, on whichever
         * thread called shutdown() - which is always the main thread (MinecraftMixin at
         * full game exit, WorldClientMixin on disconnect). With a decent backlog of
         * in-flight loads or unsaved chunks, that's a multi-second main-thread stall -
         * worse on a full game exit, where there's no "Saving world" overlay the way
         * vanilla's own world-save screen provides to explain the pause.
         *
         * Fix: do the actual waiting-and-flushing on a background daemon thread, and give
         * it a firm, short budget from the main thread's point of view
         * (SHUTDOWN_DRAIN_BUDGET_MS) instead of the effectively unbounded wait this had
         * before (10s executor termination, then however long flush() took on top of
         * that). If the drain finishes within budget, shutdown() has flushed everything
         * by the time it returns, same as before. If it doesn't, we stop waiting and let
         * the game continue closing/disconnecting; the drain thread keeps running on a
         * best-effort basis regardless.
         *
         * This trade is reasonable because what's at risk is only Bobby's own fake-chunk
         * cache, never real world save data - anything not flushed in time just gets
         * rebuilt the next time that area is visited. The thread is a daemon specifically
         * so it can never hold the JVM open waiting on its own completion.
         */
        Thread drainThread = new Thread(() -> {
            // Must finish waiting for the executor before flushing: if flush() ran first
            // and an executor task completed afterward, its saveAsync() call would add an
            // entry to pending that never gets written (silent data loss).
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
        }, "Bobby-Shutdown-Drain");
        drainThread.setDaemon(true);
        drainThread.start();

        try {
            drainThread.join(SHUTDOWN_DRAIN_BUDGET_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (drainThread.isAlive()) {
            Bobby.LOGGER.warn("Chunk save flush still running after " + SHUTDOWN_DRAIN_BUDGET_MS
                + "ms; continuing in the background instead of blocking further");
        }
    }

    public String getDebugInfo() {
        return String.format("Bobby - Fake: %d | Cache: %d | Queue: %d+%d | Known: %d | Loaded: %d | Failed: %d",
            fakeChunks.size(), cache.size(), loadQueue.size(), unloadQueue.size(),
            knownChunks.size(), totalLoaded.get(), failedLoads.get());
    }
}

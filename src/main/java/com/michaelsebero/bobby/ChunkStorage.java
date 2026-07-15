package com.michaelsebero.bobby;

import com.michaelsebero.bobby.mixin.AnvilChunkLoaderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFile;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkStorage extends AnvilChunkLoader {
    private final ConcurrentHashMap<Long, NBTTagCompound> pending = new ConcurrentHashMap<>();
    private final AtomicInteger saveCounter = new AtomicInteger(0);

    /**
     * FIX: regionCache's get-or-create (getRegionFileByRegionCoords) is fully guarded by
     * synchronizing on the map itself, making the get-check-create-put sequence atomic and
     * preventing two threads from both seeing no entry and opening the same RegionFile.
     *
     * Actual chunk I/O (getChunkDataInputStream/getChunkDataOutputStream) synchronizes on
     * the specific RegionFile instance instead of the map - see load()/save()/
     * getStoredChunks(). A single map-wide lock for every read/write serialized I/O across
     * every region file at once, even when threads were touching entirely unrelated
     * regions. Locking per-region lets independent regions proceed in parallel while still
     * fully serializing access to any one physical file.
     *
     * Because of that, eviction below must take the evicted RegionFile's own lock before
     * closing it - otherwise a close() here could run concurrently with another thread
     * still inside a synchronized(region) I/O block for that same instance.
     */
    private final Map<String, RegionFile> regionCache = new LinkedHashMap<String, RegionFile>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RegionFile> eldest) {
            if (size() > 64) {
                RegionFile evicted = eldest.getValue();
                synchronized (evicted) {
                    try {
                        evicted.close();
                    } catch (IOException e) {
                        Bobby.LOGGER.debug("Failed to close region file", e);
                    }
                }
                return true;
            }
            return false;
        }
    };

    private static Method readChunkFromNBTMethod = null;
    private static boolean reflectionFailed = false;

    public ChunkStorage(File dir) {
        super(dir, Minecraft.getMinecraft().getDataFixer());
        initializeReflection();
    }

    public static ChunkStorage create(File dir) {
        dir.mkdirs();
        return new ChunkStorage(dir);
    }

    private static void initializeReflection() {
        if (readChunkFromNBTMethod != null || reflectionFailed) {
            return;
        }

        try {
            for (Method method : AnvilChunkLoader.class.getDeclaredMethods()) {
                if (method.getReturnType() == Chunk.class &&
                    method.getParameterCount() == 2) {
                    method.setAccessible(true);
                    readChunkFromNBTMethod = method;
                    Bobby.LOGGER.info("Successfully initialized chunk deserialization reflection");
                    return;
                }
            }
            reflectionFailed = true;
            Bobby.LOGGER.warn("Could not find readChunkFromNBT method via reflection");
        } catch (Exception e) {
            reflectionFailed = true;
            Bobby.LOGGER.error("Failed to initialize reflection", e);
        }
    }

    private RegionFile getRegionFile(int chunkX, int chunkZ) throws IOException {
        return getRegionFileByRegionCoords(chunkX >> 5, chunkZ >> 5);
    }

    /**
     * Same get-or-create-and-cache logic as getRegionFile(), keyed directly by region
     * coordinates instead of chunk coordinates. getRegionFile() itself delegates here.
     *
     * Also used by getStoredChunks(), which parses region coordinates straight out of
     * each .mca filename and would otherwise have to fabricate a fake chunk coordinate
     * just to call back into the chunk-coordinate overload above.
     */
    private RegionFile getRegionFileByRegionCoords(int regionX, int regionZ) throws IOException {
        String key = regionX + "," + regionZ;

        // Synchronize the entire compound operation to prevent two threads from
        // simultaneously finding no entry and both opening the same RegionFile.
        synchronized (regionCache) {
            RegionFile region = regionCache.get(key);
            if (region != null) {
                return region;
            }

            File regionFile = new File(chunkSaveLocation, "r." + regionX + "." + regionZ + ".mca");
            region = new RegionFile(regionFile);
            regionCache.put(key, region);
            return region;
        }
    }

    private void closeAllRegions() {
        synchronized (regionCache) {
            for (RegionFile region : regionCache.values()) {
                synchronized (region) {
                    try {
                        region.close();
                    } catch (IOException e) {
                        Bobby.LOGGER.debug("Failed to close region file", e);
                    }
                }
            }
            regionCache.clear();
        }
    }

    public Set<ChunkPos> getStoredChunks() {
        Set<ChunkPos> chunks = ConcurrentHashMap.newKeySet();

        File regionDir = chunkSaveLocation;
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return chunks;
        }

        File[] regionFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (regionFiles == null || regionFiles.length == 0) {
            return chunks;
        }

        Arrays.stream(regionFiles).parallel().forEach(regionFile -> {
            try {
                String name = regionFile.getName();
                String[] parts = name.replace(".mca", "").split("\\.");
                if (parts.length != 3) return;

                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);

                /**
                 * FIX: this used to open a brand new RegionFile(regionFile) here,
                 * completely bypassing regionCache/getRegionFile(). This scan runs at
                 * startup via ChunkManager.discoverKnownChunks(), potentially while
                 * loads/saves are already streaming through the cached path for the
                 * same on-disk files - so this could end up with two independent,
                 * unsynchronized RegionFile instances open on the same underlying
                 * .mca file at once, each with its own in-memory view of that file's
                 * header/sector table. Routing through the same cache as every other
                 * read/write means there's only ever one RegionFile per physical file.
                 *
                 * Do NOT close the returned RegionFile here - it's shared/cached, and
                 * closing it would pull it out from under any other in-flight load or
                 * save using the same cache entry. The cache's own LRU eviction
                 * (removeEldestEntry, above) owns closing region files.
                 */
                RegionFile region = getRegionFileByRegionCoords(regionX, regionZ);

                for (int cx = 0; cx < 32; cx++) {
                    for (int cz = 0; cz < 32; cz++) {
                        DataInputStream in = null;
                        try {
                            synchronized (region) {
                                in = region.getChunkDataInputStream(cx, cz);
                            }
                            if (in != null) {
                                int chunkX = (regionX << 5) + cx;
                                int chunkZ = (regionZ << 5) + cz;
                                chunks.add(new ChunkPos(chunkX, chunkZ));
                                in.close();
                            }
                        } catch (IOException e) {
                            Bobby.LOGGER.debug("Failed to read chunk data", e);
                        } finally {
                            if (in != null) {
                                try { in.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Bobby.LOGGER.debug("Failed to scan region file: " + regionFile.getName(), e);
            }
        });

        return chunks;
    }

    @Nullable
    public NBTTagCompound load(ChunkPos pos) {
        try {
            RegionFile region = getRegionFile(pos.x, pos.z);
            DataInputStream in;
            synchronized (region) {
                in = region.getChunkDataInputStream(pos.x & 31, pos.z & 31);
            }

            if (in == null) {
                return null;
            }

            try {
                NBTTagCompound nbt = CompressedStreamTools.read(in);
                if (nbt == null) {
                    return null;
                }
                return Minecraft.getMinecraft().getDataFixer().process(FixTypes.CHUNK, nbt);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Bobby.LOGGER.debug("Failed to load chunk at " + pos, e);
            return null;
        }
    }

    @Nullable
    public Chunk deserialize(ChunkPos pos, NBTTagCompound nbt, WorldClient world) {
        if (nbt == null) {
            return null;
        }

        try {
            NBTTagCompound levelTag = nbt.getCompoundTag("Level");
            if (levelTag == null || levelTag.isEmpty()) {
                return null;
            }

            Chunk chunk = null;

            if (readChunkFromNBTMethod != null && !reflectionFailed) {
                try {
                    chunk = (Chunk) readChunkFromNBTMethod.invoke(this, world, levelTag);
                } catch (Exception e) {
                    Bobby.LOGGER.debug("Reflection deserialization failed, using fallback", e);
                }
            }

            if (chunk == null) {
                Object[] data = checkedReadChunkFromNBT__Async(world, pos.x, pos.z, nbt);
                if (data != null && data[0] instanceof Chunk) {
                    chunk = (Chunk) data[0];
                }
            }

            if (chunk != null) {
                try {
                    loadEntities(world, levelTag, chunk);
                } catch (Exception e) {
                    Bobby.LOGGER.debug("Failed to load entities for chunk at " + pos, e);
                }
            }

            return chunk;

        } catch (Exception e) {
            Bobby.LOGGER.debug("Failed to deserialize chunk at " + pos, e);
            return null;
        }
    }

    @Nullable
    public NBTTagCompound serialize(Chunk chunk) {
        try {
            NBTTagCompound root = new NBTTagCompound();
            NBTTagCompound level = new NBTTagCompound();
            root.setTag("Level", level);
            root.setInteger("DataVersion", 1343);

            ((AnvilChunkLoaderAccessor) this).invokeWriteChunkToNBT(chunk, chunk.getWorld(), level);
            return root;
        } catch (Exception e) {
            Bobby.LOGGER.debug("Failed to serialize chunk at " + chunk.getPos(), e);
            return null;
        }
    }

    public void saveAsync(ChunkPos pos, NBTTagCompound nbt) {
        if (nbt != null) {
            pending.put(ChunkPos.asLong(pos.x, pos.z), nbt);
        }
    }

    public void save() {
        if (pending.isEmpty()) return;

        int maxPerBatch = Math.min(20, pending.size());
        List<Long> toRemove = new ArrayList<>();
        int count = 0;

        for (Map.Entry<Long, NBTTagCompound> entry : pending.entrySet()) {
            if (count >= maxPerBatch) break;

            long packed = entry.getKey();
            int x = (int) packed;
            int z = (int) (packed >> 32);

            try {
                RegionFile region = getRegionFile(x, z);
                DataOutputStream out;
                synchronized (region) {
                    out = region.getChunkDataOutputStream(x & 31, z & 31);
                }

                if (out != null) {
                    try {
                        CompressedStreamTools.write(entry.getValue(), out);
                        toRemove.add(packed);
                        count++;
                    } finally {
                        out.close();
                    }
                } else {
                    toRemove.add(packed);
                }
            } catch (Exception e) {
                Bobby.LOGGER.debug("Failed to save chunk at " + x + ", " + z, e);
                toRemove.add(packed);
            }
        }

        toRemove.forEach(pending::remove);

        if (count > 0) {
            saveCounter.addAndGet(count);
        }
    }

    /**
     * FIX (Bug 5): Loop until truly empty rather than bounding by iteration count.
     * save() always removes entries (even failed ones), so the loop must terminate.
     */
    public void flush() {
        while (!pending.isEmpty()) {
            save();
        }
        closeAllRegions();
    }

    public int getPendingCount() {
        return pending.size();
    }
}

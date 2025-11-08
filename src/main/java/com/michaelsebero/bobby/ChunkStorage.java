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

/**
 * Handles chunk NBT serialization and disk I/O
 * PERFORMANCE OPTIMIZED: Async saves, reduced blocking
 * FIXED: Thread-safe data fixing + universal deserialization for all world types
 */
public class ChunkStorage extends AnvilChunkLoader {
    private final ConcurrentHashMap<Long, NBTTagCompound> pending = new ConcurrentHashMap<>();
    private final AtomicInteger saveCounter = new AtomicInteger(0);
    
    // Our own region file cache for Bobby's directory
    private final Map<String, RegionFile> regionCache = new HashMap<>();
    
    // Cache reflection method for universal chunk reading
    private static Method readChunkFromNBTMethod = null;
    private static boolean reflectionFailed = false;
    
    // CRITICAL: Synchronize data fixer access to prevent ConcurrentModificationException
    private static final Object dataFixerLock = new Object();
    
    public ChunkStorage(File dir) {
        super(dir, Minecraft.getMinecraft().getDataFixer());
        initializeReflection();
    }
    
    public static ChunkStorage create(File dir) {
        dir.mkdirs();
        return new ChunkStorage(dir);
    }

    /**
     * Initialize reflection access to AnvilChunkLoader's readChunkFromNBT method
     * This method is more universal and doesn't depend on world generators
     */
    private static void initializeReflection() {
        if (readChunkFromNBTMethod != null || reflectionFailed) {
            return;
        }
        
        try {
            // Try to find the readChunkFromNBT method
            // This is the lower-level method that just reads NBT data without generator logic
            for (Method method : AnvilChunkLoader.class.getDeclaredMethods()) {
                // Look for method with signature: Chunk readChunkFromNBT(World, NBTTagCompound)
                if (method.getReturnType() == Chunk.class && 
                    method.getParameterCount() == 2) {
                    method.setAccessible(true);
                    readChunkFromNBTMethod = method;
                    Bobby.LOGGER.info("Successfully initialized reflection for universal chunk deserialization");
                    break;
                }
            }
            
            if (readChunkFromNBTMethod == null) {
                Bobby.LOGGER.warn("Could not find readChunkFromNBT method, falling back to standard deserialization");
                reflectionFailed = true;
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to initialize reflection for chunk deserialization", e);
            reflectionFailed = true;
        }
    }

    /**
     * Get or create a RegionFile for Bobby's storage
     */
    private synchronized RegionFile getRegionFile(int chunkX, int chunkZ) throws IOException {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String key = regionX + "," + regionZ;
        
        RegionFile region = regionCache.get(key);
        if (region != null) {
            return region;
        }
        
        File regionFile = new File(chunkSaveLocation, "r." + regionX + "." + regionZ + ".mca");
        region = new RegionFile(regionFile);
        regionCache.put(key, region);
        
        Bobby.LOGGER.debug("Created/opened region file: {}", regionFile.getName());
        return region;
    }
    
    /**
     * Close all region files
     */
    private synchronized void closeAllRegions() {
        for (RegionFile region : regionCache.values()) {
            try {
                region.close();
            } catch (IOException e) {
                Bobby.LOGGER.error("Error closing region file", e);
            }
        }
        regionCache.clear();
    }

    /**
     * Scan region files to discover all stored chunks
     */
    public Set<ChunkPos> getStoredChunks() {
        Set<ChunkPos> chunks = new HashSet<>();
        
        File regionDir = chunkSaveLocation;
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            Bobby.LOGGER.info("Region directory does not exist: {}", regionDir.getAbsolutePath());
            return chunks;
        }
        
        File[] regionFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (regionFiles == null || regionFiles.length == 0) {
            Bobby.LOGGER.info("No region files found in: {}", regionDir.getAbsolutePath());
            return chunks;
        }
        
        Bobby.LOGGER.info("Scanning {} region files...", regionFiles.length);
        
        for (File regionFile : regionFiles) {
            try {
                String name = regionFile.getName();
                String[] parts = name.replace(".mca", "").split("\\.");
                if (parts.length != 3) continue;
                
                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);
                
                RegionFile region = new RegionFile(regionFile);
                
                for (int cx = 0; cx < 32; cx++) {
                    for (int cz = 0; cz < 32; cz++) {
                        int chunkX = (regionX << 5) + cx;
                        int chunkZ = (regionZ << 5) + cz;
                        
                        try {
                            DataInputStream in = region.getChunkDataInputStream(cx, cz);
                            if (in != null) {
                                chunks.add(new ChunkPos(chunkX, chunkZ));
                                in.close();
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
                
                region.close();
                
            } catch (Exception e) {
                Bobby.LOGGER.debug("Failed to scan region file: {}", regionFile.getName(), e);
            }
        }
        
        Bobby.LOGGER.info("Found {} cached chunks in {} region files", chunks.size(), regionFiles.length);
        return chunks;
    }

    @Nullable
    public NBTTagCompound load(ChunkPos pos) {
        try {
            RegionFile region = getRegionFile(pos.x, pos.z);
            DataInputStream in = region.getChunkDataInputStream(pos.x & 31, pos.z & 31);
            
            if (in == null) {
                Bobby.LOGGER.debug("No data stream for chunk {} - not in region file", pos);
                return null;
            }
            
            NBTTagCompound nbt = CompressedStreamTools.read(in);
            in.close();
            
            if (nbt == null) {
                Bobby.LOGGER.warn("NBT is null after reading for chunk {}", pos);
                return null;
            }
            
            // CRITICAL: Synchronize data fixer access to prevent concurrent modification
            // Forge's ModFixs uses a HashMap that's not thread-safe
            NBTTagCompound fixed;
            synchronized (dataFixerLock) {
                fixed = Minecraft.getMinecraft().getDataFixer().process(FixTypes.CHUNK, nbt);
            }
            
            Bobby.LOGGER.debug("Successfully loaded and fixed chunk {}", pos);
            return fixed;
        } catch (Exception e) {
            Bobby.LOGGER.error("Exception loading chunk {} from disk", pos, e);
            return null;
        }
    }

    @Nullable
    public Chunk deserialize(ChunkPos pos, NBTTagCompound nbt, WorldClient world) {
        try {
            Bobby.LOGGER.debug("Deserializing chunk {} using universal method", pos);
            
            if (nbt == null) {
                Bobby.LOGGER.warn("Cannot deserialize null NBT for chunk {}", pos);
                return null;
            }
            
            Chunk chunk = null;
            
            // Try reflection-based universal deserialization first
            if (readChunkFromNBTMethod != null && !reflectionFailed) {
                try {
                    // Extract the Level tag which contains the chunk data
                    NBTTagCompound levelTag = nbt.getCompoundTag("Level");
                    if (levelTag != null && !levelTag.isEmpty()) {
                        // Call the low-level readChunkFromNBT method directly
                        chunk = (Chunk) readChunkFromNBTMethod.invoke(this, world, levelTag);
                        
                        if (chunk != null) {
                            Bobby.LOGGER.debug("Successfully deserialized chunk {} using reflection", pos);
                            return chunk;
                        }
                    }
                } catch (Exception e) {
                    Bobby.LOGGER.debug("Reflection-based deserialization failed for chunk {}, trying fallback", pos, e);
                    chunk = null;
                }
            }
            
            // Fallback: Use the standard async method
            if (chunk == null) {
                Bobby.LOGGER.debug("Using standard deserialization for chunk {}", pos);
                Object[] data = checkedReadChunkFromNBT__Async(world, pos.x, pos.z, nbt);
                
                if (data == null) {
                    Bobby.LOGGER.warn("checkedReadChunkFromNBT returned null for chunk {}", pos);
                    return null;
                }
                
                chunk = (Chunk) data[0];
                NBTTagCompound levelTag = (NBTTagCompound) data[1];
                
                if (chunk == null) {
                    Bobby.LOGGER.warn("Chunk object is null after deserialization for {}", pos);
                    return null;
                }
                
                // Try to load entities, but don't fail if it doesn't work
                if (levelTag != null && levelTag.hasKey("Level")) {
                    try {
                        loadEntities(world, levelTag.getCompoundTag("Level"), chunk);
                    } catch (Exception e) {
                        Bobby.LOGGER.debug("Failed to load entities for chunk {}, continuing anyway", pos, e);
                    }
                }
            }
            
            Bobby.LOGGER.debug("Successfully deserialized chunk {}", pos);
            return chunk;
            
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to deserialize chunk {}", pos, e);
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
            
            try {
                ((AnvilChunkLoaderAccessor) this).invokeWriteChunkToNBT(chunk, chunk.getWorld(), level);
                Bobby.LOGGER.debug("Successfully serialized chunk {}", chunk.getPos());
                return root;
            } catch (ConcurrentModificationException e) {
                Bobby.LOGGER.debug("Chunk {} is being modified, will retry later", chunk.getPos());
                return null;
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Failed to serialize chunk {}", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * Queue chunk for async save - does not block
     */
    public void saveAsync(ChunkPos pos, NBTTagCompound nbt) {
        if (nbt != null) {
            pending.put(ChunkPos.asLong(pos.x, pos.z), nbt);
        }
    }

    /**
     * Process pending saves in batches
     */
    public void save() {
        if (pending.isEmpty()) return;
        
        int count = 0;
        int maxPerBatch = Math.min(20, pending.size());
        
        List<Long> toRemove = new ArrayList<>();
        
        for (Map.Entry<Long, NBTTagCompound> entry : pending.entrySet()) {
            if (count >= maxPerBatch) break;
            
            long packed = entry.getKey();
            int x = (int) packed;
            int z = (int) (packed >> 32);
            
            try {
                RegionFile region = getRegionFile(x, z);
                DataOutputStream out = region.getChunkDataOutputStream(x & 31, z & 31);
                
                if (out != null) {
                    CompressedStreamTools.write(entry.getValue(), out);
                    out.close();
                    toRemove.add(packed);
                    count++;
                    Bobby.LOGGER.debug("Successfully wrote chunk ({}, {}) to Bobby's region file", x, z);
                } else {
                    Bobby.LOGGER.warn("Could not get output stream for chunk ({}, {})", x, z);
                    toRemove.add(packed);
                }
            } catch (Exception e) {
                Bobby.LOGGER.error("Failed to write chunk ({}, {}) to disk", x, z, e);
                toRemove.add(packed);
            }
        }
        
        for (Long key : toRemove) {
            pending.remove(key);
        }
        
        if (count > 0) {
            saveCounter.addAndGet(count);
            
            if (saveCounter.get() % 100 == 0) {
                Bobby.LOGGER.info("Saved {} chunks to Bobby storage, {} pending", saveCounter.get(), pending.size());
            }
        }
    }

    /**
     * Flush all pending saves - blocking operation
     */
    public void flush() {
        Bobby.LOGGER.info("Flushing {} pending chunks...", pending.size());
        int flushed = 0;
        
        while (!pending.isEmpty() && flushed < 1000) {
            save();
            flushed++;
            
            if (flushed % 50 == 0) {
                Bobby.LOGGER.info("Flushed {} batches, {} remaining", flushed, pending.size());
            }
        }
        
        closeAllRegions();
        
        if (!pending.isEmpty()) {
            Bobby.LOGGER.warn("Could not flush all chunks, {} remaining", pending.size());
        } else {
            Bobby.LOGGER.info("Successfully flushed all chunks to Bobby storage");
        }
    }
    
    public int getPendingCount() {
        return pending.size();
    }
}

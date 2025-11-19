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
    
    private final Map<String, RegionFile> regionCache = new HashMap<>();
    
    private static Method readChunkFromNBTMethod = null;
    private static boolean reflectionFailed = false;
    
    private static final Object dataFixerLock = new Object();
    
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
                    break;
                }
            }
            
            if (readChunkFromNBTMethod == null) {
                reflectionFailed = true;
            }
        } catch (Exception e) {
            reflectionFailed = true;
        }
    }

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
        
        return region;
    }
    
    private synchronized void closeAllRegions() {
        for (RegionFile region : regionCache.values()) {
            try {
                region.close();
            } catch (IOException e) {
                // Silent fail
            }
        }
        regionCache.clear();
    }

    public Set<ChunkPos> getStoredChunks() {
        Set<ChunkPos> chunks = new HashSet<>();
        
        File regionDir = chunkSaveLocation;
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return chunks;
        }
        
        File[] regionFiles = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
        if (regionFiles == null || regionFiles.length == 0) {
            return chunks;
        }
        
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
                // Silent fail
            }
        }
        
        return chunks;
    }

    @Nullable
    public NBTTagCompound load(ChunkPos pos) {
        try {
            RegionFile region = getRegionFile(pos.x, pos.z);
            DataInputStream in = region.getChunkDataInputStream(pos.x & 31, pos.z & 31);
            
            if (in == null) {
                return null;
            }
            
            NBTTagCompound nbt = CompressedStreamTools.read(in);
            in.close();
            
            if (nbt == null) {
                return null;
            }
            
            NBTTagCompound fixed;
            synchronized (dataFixerLock) {
                fixed = Minecraft.getMinecraft().getDataFixer().process(FixTypes.CHUNK, nbt);
            }
            
            return fixed;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public Chunk deserialize(ChunkPos pos, NBTTagCompound nbt, WorldClient world) {
        try {
            if (nbt == null) {
                return null;
            }
            
            Chunk chunk = null;
            
            if (readChunkFromNBTMethod != null && !reflectionFailed) {
                try {
                    NBTTagCompound levelTag = nbt.getCompoundTag("Level");
                    if (levelTag != null && !levelTag.isEmpty()) {
                        chunk = (Chunk) readChunkFromNBTMethod.invoke(this, world, levelTag);
                        
                        if (chunk != null) {
                            try {
                                loadEntities(world, levelTag, chunk);
                            } catch (Exception e) {
                                // Continue anyway
                            }
                            
                            return chunk;
                        }
                    }
                } catch (Exception e) {
                    chunk = null;
                }
            }
            
            if (chunk == null) {
                Object[] data = checkedReadChunkFromNBT__Async(world, pos.x, pos.z, nbt);
                
                if (data == null) {
                    return null;
                }
                
                chunk = (Chunk) data[0];
                NBTTagCompound levelTag = (NBTTagCompound) data[1];
                
                if (chunk == null) {
                    return null;
                }
                
                if (levelTag != null && levelTag.hasKey("Level")) {
                    try {
                        NBTTagCompound level = levelTag.getCompoundTag("Level");
                        loadEntities(world, level, chunk);
                    } catch (Exception e) {
                        // Continue anyway
                    }
                }
            }
            
            return chunk;
            
        } catch (Exception e) {
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
                return root;
            } catch (ConcurrentModificationException e) {
                return null;
            }
        } catch (Exception e) {
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
                } else {
                    toRemove.add(packed);
                }
            } catch (Exception e) {
                toRemove.add(packed);
            }
        }
        
        for (Long key : toRemove) {
            pending.remove(key);
        }
        
        if (count > 0) {
            saveCounter.addAndGet(count);
        }
    }

    public void flush() {
        int flushed = 0;
        
        while (!pending.isEmpty() && flushed < 1000) {
            save();
            flushed++;
        }
        
        closeAllRegions();
    }
    
    public int getPendingCount() {
        return pending.size();
    }
}

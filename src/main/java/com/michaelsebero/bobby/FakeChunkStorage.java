package com.michaelsebero.bobby;

import com.michaelsebero.bobby.ext.AnvilChunkLoaderExt;
import com.michaelsebero.bobby.mixin.AnvilChunkLoaderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class FakeChunkStorage extends AnvilChunkLoader {
    private final Map<Long, NBTTagCompound> pendingSaves = new ConcurrentHashMap<>();
    
    public FakeChunkStorage(File file, DataFixer fixer) {
        super(file, fixer);
        if(BobbyConfig.noBlockEntities) {
            ((AnvilChunkLoaderExt)this).bobby$setLoadsTileEntities(false);
        }
    }
    
    public static FakeChunkStorage getFor(File file, BiomeProvider provider) {
        // Ensure directory exists
        if (!file.exists()) {
            file.mkdirs();
        }
        return new FakeChunkStorage(file, Minecraft.getMinecraft().getDataFixer());
    }
    
    @Nullable
    public NBTTagCompound loadTag(ChunkPos pos) throws IOException {
        DataInputStream dataInputStream = null;
        try {
            dataInputStream = RegionFileCache.getChunkInputStream(this.chunkSaveLocation, pos.x, pos.z);

            if (dataInputStream == null) {
                return null;
            }

            NBTTagCompound compound = CompressedStreamTools.read(dataInputStream);
            
            // Apply data fixer
            if (compound != null) {
                compound = Minecraft.getMinecraft().getDataFixer().process(FixTypes.CHUNK, compound);
            }
            
            return compound;
        } catch (IOException e) {
            Bobby.LOGGER.error("Failed to load chunk at {}: {}", pos, e.getMessage());
            throw e;
        } finally {
            if (dataInputStream != null) {
                try {
                    dataInputStream.close();
                } catch (IOException e) {
                    Bobby.LOGGER.error("Failed to close input stream: {}", e.getMessage());
                }
            }
        }
    }
    
    @Nullable
    public Supplier<Chunk> deserialize(ChunkPos pos, NBTTagCompound tag, WorldClient world) {
        try {
            Object[] data = this.checkedReadChunkFromNBT__Async(world, pos.x, pos.z, tag);
            if(data != null) {
                Chunk chunk = (Chunk)data[0];
                NBTTagCompound nbttagcompound = (NBTTagCompound) data[1];
                
                // Validate chunk data
                if (chunk == null) {
                    Bobby.LOGGER.error("Chunk deserialization returned null for {}", pos);
                    return null;
                }
                
                return () -> {
                    try {
                        this.loadEntities(world, nbttagcompound.getCompoundTag("Level"), chunk);
                        return chunk;
                    } catch (Exception e) {
                        Bobby.LOGGER.error("Error loading entities for chunk {}: {}", pos, e.getMessage());
                        return chunk; // Return chunk without entities rather than failing completely
                    }
                };
            }
        } catch (Exception e) {
            Bobby.LOGGER.error("Error deserializing chunk {}", pos, e);
        }
        return null;
    }
    
    @Nullable
    public NBTTagCompound serialize(Chunk chunk) {
        try {
            NBTTagCompound nbttagcompound = new NBTTagCompound();
            NBTTagCompound nbttagcompound1 = new NBTTagCompound();
            nbttagcompound.setTag("Level", nbttagcompound1);
            nbttagcompound.setInteger("DataVersion", 1343);
            net.minecraftforge.fml.common.FMLCommonHandler.instance().getDataFixer().writeVersionData(nbttagcompound);
            
            ((AnvilChunkLoaderAccessor)this).invokeWriteChunkToNBT(chunk, chunk.getWorld(), nbttagcompound1);
            
            return nbttagcompound;
        } catch (Exception e) {
            Bobby.LOGGER.error("Error serializing chunk at {}", chunk.getPos(), e);
            return null;
        }
    }
    
    public void save(ChunkPos pos, NBTTagCompound compound) {
        if (compound == null) {
            Bobby.LOGGER.error("Attempted to save null compound for chunk {}", pos);
            return;
        }
        
        long posLong = ChunkPos.asLong(pos.x, pos.z);
        
        if (BobbyConfig.asyncSaving) {
            // Store in pending saves for async processing
            pendingSaves.put(posLong, compound);
        } else {
            // Save immediately (parent class is thread-safe)
            this.addChunkToPending(pos, compound);
        }
    }
    
    /**
     * Process a batch of pending saves
     * Should be called from async thread
     */
    public void processPendingSaves() {
        if (pendingSaves.isEmpty()) {
            return;
        }
        
        // Process up to 10 saves per call to avoid blocking too long
        int processed = 0;
        final int maxBatch = 10;
        
        for (Map.Entry<Long, NBTTagCompound> entry : pendingSaves.entrySet()) {
            if (processed >= maxBatch) {
                break;
            }
            
            Long posLong = entry.getKey();
            NBTTagCompound compound = pendingSaves.remove(posLong);
            
            if (compound != null) {
                int x = ChunkPosHelper.getPackedX(posLong);
                int z = ChunkPosHelper.getPackedZ(posLong);
                
                try {
                    this.addChunkToPending(new ChunkPos(x, z), compound);
                    processed++;
                } catch (Exception e) {
                    Bobby.LOGGER.error("Error saving chunk at {}, {}: {}", x, z, e.getMessage());
                    // Re-add to pending if save failed
                    pendingSaves.put(posLong, compound);
                }
            }
        }
    }
    
    /**
     * Flush all pending saves to disk
     * Should be called before shutdown
     */
    public void flushPendingSaves() {
        Bobby.LOGGER.info("Flushing {} pending saves", pendingSaves.size());
        
        while (!pendingSaves.isEmpty()) {
            processPendingSaves();
        }
        
        // Flush the underlying storage
        try {
            this.writeNextIO();
        } catch (Exception e) {
            Bobby.LOGGER.error("Error flushing storage", e);
        }
    }
    
    public int getPendingSaveCount() {
        return pendingSaves.size();
    }
}

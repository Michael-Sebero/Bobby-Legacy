package com.michaelsebero.bobby;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;

/**
 * PURE VISUAL CHUNK - Never ticks, never updates, never interacts
 * This is a frozen snapshot for rendering only
 */
public class FakeChunk extends Chunk {
    
    // Properly sized empty entity array to prevent render crashes
    private static final ClassInheritanceMultiMap<Entity>[] EMPTY_ENTITY_ARRAY = createEmptyEntityArray();
    
    @SuppressWarnings("unchecked")
    private static ClassInheritanceMultiMap<Entity>[] createEmptyEntityArray() {
        ClassInheritanceMultiMap<Entity>[] array = new ClassInheritanceMultiMap[16];
        for (int i = 0; i < 16; i++) {
            array[i] = new ClassInheritanceMultiMap<>(Entity.class);
        }
        return array;
    }
    
    public FakeChunk(World world, int x, int z) {
        super(world, x, z);
    }
    
    public void copyFrom(Chunk source) {
        // Copy ONLY visual data - blocks, biomes, heightmap
        ExtendedBlockStorage[] src = source.getBlockStorageArray();
        ExtendedBlockStorage[] dst = this.getBlockStorageArray();
        
        for (int i = 0; i < Math.min(src.length, dst.length); i++) {
            if (src[i] != null) {
                dst[i] = src[i];
            }
        }
        
        // Copy biomes for proper visual coloring
        byte[] srcBiomes = source.getBiomeArray();
        byte[] dstBiomes = this.getBiomeArray();
        System.arraycopy(srcBiomes, 0, dstBiomes, 0, Math.min(srcBiomes.length, dstBiomes.length));
        
        // Copy heightmap for proper rendering/occlusion
        int[] srcHeight = source.getHeightMap();
        int[] dstHeight = this.getHeightMap();
        System.arraycopy(srcHeight, 0, dstHeight, 0, Math.min(srcHeight.length, dstHeight.length));
        
        // NEVER COPY: Entities, tile entities, scheduled ticks, or any active data
    }
    
    // ===== PREVENT ALL TICKING AND UPDATES =====
    
    @Override
    public void onTick(boolean skipRecheckGaps) {
        // NEVER TICK - This is a frozen visual snapshot
    }
    
    @Override
    public void onLoad() {
        // Already "loaded" - do nothing
    }
    
    @Override
    public void onUnload() {
        // Just cleanup, no actual unload logic needed
    }
    
    // ===== PREVENT ALL MODIFICATIONS =====
    
    @Override
    public IBlockState setBlockState(BlockPos pos, IBlockState state) {
        // Fake chunks are read-only screenshots
        return getBlockState(pos);
    }
    
    @Override
    public void setModified(boolean modified) {
        // Never modified, never needs saving
    }
    
    @Override
    public boolean needsSaving(boolean always) {
        return false;
    }
    
    @Override
    public void markDirty() {
        // Never dirty
    }
    
    // ===== PREVENT ALL LIGHTING UPDATES =====
    
    @Override
    public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) {
        // Lighting is frozen from the snapshot
    }
    
    @Override
    public void generateSkylightMap() {
        // Already has lighting from original chunk
    }
    
    @Override
    public void checkLight() {
        // No light updates
    }
    
    // ===== PREVENT ALL ENTITY INTERACTIONS =====
    
    @Override
    public void addEntity(Entity entity) {
        // Fake chunks cannot have active entities
    }
    
    @Override
    public void removeEntity(Entity entity) {
        // No entities to remove
    }
    
    @Override
    public void removeEntityAtIndex(Entity entity, int index) {
        // No entities
    }
    
    @Override
    public boolean isEmpty() {
        return false; // Has blocks (for rendering)
    }
    
    @Override
    public ClassInheritanceMultiMap<Entity>[] getEntityLists() {
        // CRITICAL: Return properly sized array to prevent render crashes
        return EMPTY_ENTITY_ARRAY;
    }
    
    // ===== PREVENT ALL TILE ENTITY INTERACTIONS =====
    
    @Override
    public void addTileEntity(TileEntity te) {
        // NEVER add tile entities - they cause ticking
    }
    
    @Override
    public void addTileEntity(BlockPos pos, TileEntity te) {
        // NEVER add tile entities
    }
    
    @Override
    public void removeTileEntity(BlockPos pos) {
        // No tile entities to remove
    }
    
    @Override
    @Nullable
    public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType type) {
        // Return null - most renderers handle this gracefully
        // Fake chunks show blocks without interactive behavior
        return null;
    }
    
    @Override
    public Map<BlockPos, TileEntity> getTileEntityMap() {
        return Collections.emptyMap();
    }
    
    // ===== FROZEN STATE FLAGS =====
    
    @Override
    public boolean isLoaded() {
        return true; // Always "loaded" for rendering
    }
    
    @Override
    public boolean isPopulated() {
        return true; // Always "populated"
    }
    
    @Override
    public boolean isTerrainPopulated() {
        return true;
    }
    
    @Override
    public void setTerrainPopulated(boolean terrainPopulated) {
        // Always populated
    }
    
    // ===== VISUAL DATA ONLY =====
    
    @Override
    public IBlockState getBlockState(BlockPos pos) {
        // This is the ONLY thing we actually do - return block visual data
        return super.getBlockState(pos);
    }
    
    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        // Return cached lighting from snapshot
        return super.getLightFor(type, pos);
    }
    
    @Override
    public int getLightSubtracted(BlockPos pos, int amount) {
        // Return cached lighting
        return super.getLightSubtracted(pos, amount);
    }
}

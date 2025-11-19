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
import java.util.HashMap;
import java.util.Map;

public class FakeChunk extends Chunk {
    
    private static final ClassInheritanceMultiMap<Entity>[] EMPTY_ENTITY_ARRAY = createEmptyEntityArray();
    
    private final Map<BlockPos, TileEntity> frozenTileEntities = new HashMap<>();
    
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
        ExtendedBlockStorage[] src = source.getBlockStorageArray();
        ExtendedBlockStorage[] dst = this.getBlockStorageArray();
        
        for (int i = 0; i < Math.min(src.length, dst.length); i++) {
            if (src[i] != null && src[i] != Chunk.NULL_BLOCK_STORAGE) {
                dst[i] = src[i];
            }
        }
        
        byte[] srcBiomes = source.getBiomeArray();
        byte[] dstBiomes = this.getBiomeArray();
        System.arraycopy(srcBiomes, 0, dstBiomes, 0, Math.min(srcBiomes.length, dstBiomes.length));
        
        int[] srcHeight = source.getHeightMap();
        int[] dstHeight = this.getHeightMap();
        System.arraycopy(srcHeight, 0, dstHeight, 0, Math.min(srcHeight.length, dstHeight.length));
        
        Map<BlockPos, TileEntity> sourceTileEntities = source.getTileEntityMap();
        if (sourceTileEntities != null && !sourceTileEntities.isEmpty()) {
            for (Map.Entry<BlockPos, TileEntity> entry : sourceTileEntities.entrySet()) {
                BlockPos pos = entry.getKey();
                TileEntity te = entry.getValue();
                
                if (te != null && !te.isInvalid()) {
                    frozenTileEntities.put(pos, te);
                }
            }
        }
        
        this.setTerrainPopulated(true);
        this.setLightPopulated(true);
    }
    
    @Override
    public void onTick(boolean skipRecheckGaps) {
    }
    
    @Override
    public void onLoad() {
    }
    
    @Override
    public void onUnload() {
        frozenTileEntities.clear();
    }
    
    @Override
    public IBlockState setBlockState(BlockPos pos, IBlockState state) {
        return getBlockState(pos);
    }
    
    @Override
    public void setModified(boolean modified) {
    }
    
    @Override
    public boolean needsSaving(boolean always) {
        return false;
    }
    
    @Override
    public void markDirty() {
    }
    
    @Override
    public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) {
    }
    
    @Override
    public void generateSkylightMap() {
    }
    
    @Override
    public void checkLight() {
    }
    
    @Override
    public void addEntity(Entity entity) {
    }
    
    @Override
    public void removeEntity(Entity entity) {
    }
    
    @Override
    public void removeEntityAtIndex(Entity entity, int index) {
    }
    
    @Override
    public boolean isEmpty() {
        return false;
    }
    
    @Override
    public ClassInheritanceMultiMap<Entity>[] getEntityLists() {
        return EMPTY_ENTITY_ARRAY;
    }
    
    @Override
    public void addTileEntity(TileEntity te) {
    }
    
    @Override
    public void addTileEntity(BlockPos pos, TileEntity te) {
    }
    
    @Override
    public void removeTileEntity(BlockPos pos) {
    }
    
    @Override
    @Nullable
    public TileEntity getTileEntity(BlockPos pos, Chunk.EnumCreateEntityType type) {
        TileEntity te = frozenTileEntities.get(pos);
        
        if (te != null && !te.isInvalid()) {
            return te;
        }
        
        return null;
    }
    
    @Override
    public Map<BlockPos, TileEntity> getTileEntityMap() {
        return Collections.unmodifiableMap(frozenTileEntities);
    }
    
    @Override
    public boolean isLoaded() {
        return true;
    }
    
    @Override
    public boolean isPopulated() {
        return true;
    }
    
    @Override
    public boolean isTerrainPopulated() {
        return true;
    }
    
    @Override
    public void setTerrainPopulated(boolean terrainPopulated) {
    }
    
    @Override
    public boolean isLightPopulated() {
        return true;
    }
    
    @Override
    public void setLightPopulated(boolean lightPopulated) {
    }
    
    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return super.getBlockState(pos);
    }
    
    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        return super.getLightFor(type, pos);
    }
    
    @Override
    public int getLightSubtracted(BlockPos pos, int amount) {
        return super.getLightSubtracted(pos, amount);
    }
}

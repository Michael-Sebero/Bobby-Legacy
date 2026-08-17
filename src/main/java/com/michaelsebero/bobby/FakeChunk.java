package com.michaelsebero.bobby;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FakeChunk extends Chunk {

    /**
     * Reflection handle for ExtendedBlockStorage.blockRefCount.
     *
     * AnvilChunkLoader deserialization writes block data directly into the
     * BlockStateContainer's backing array, completely bypassing the
     * ExtendedBlockStorage.set(x,y,z,state) method. That method is the only place
     * blockRefCount is updated, so every deserialized section ends up with
     * blockRefCount == 0. isEmpty() returns (blockRefCount == 0), so every section
     * reports itself as empty to the renderer.
     *
     * RenderChunk.rebuildChunk() skips any section where isEmpty() is true, meaning
     * no geometry is ever compiled for those sections — they are permanently invisible
     * at all distances, even up close, and newly placed blocks inside them are also
     * invisible because they join the same empty compiled mesh bucket.
     *
     * We resolve this by scanning all fields on ExtendedBlockStorage once at class
     * load time to cache a Field reference. MCP name is tried first, then SRG, then
     * a heuristic (first private non-static int field) as a last resort — there is
     * only one such candidate in 1.12.2 EBS.
     */
    private static final Field BLOCK_REF_COUNT_FIELD = resolveBlockRefCountField();

    private static Field resolveBlockRefCountField() {
        String[] candidates = { "blockRefCount", "field_76680_d" };

        for (String name : candidates) {
            try {
                Field f = ExtendedBlockStorage.class.getDeclaredField(name);
                f.setAccessible(true);
                Bobby.LOGGER.info("Bobby: resolved EBS.blockRefCount as '" + name + "'");
                return f;
            } catch (NoSuchFieldException ignored) { }
        }

        // Last resort: first private non-static int field on EBS
        for (Field f : ExtendedBlockStorage.class.getDeclaredFields()) {
            if (f.getType() == int.class
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Bobby.LOGGER.warn("Bobby: resolved EBS.blockRefCount via heuristic (field: " + f.getName() + ")");
                return f;
            }
        }

        Bobby.LOGGER.error("Bobby: could not resolve EBS.blockRefCount — upper chunk sections will be invisible");
        return null;
    }

    /**
     * Count the non-air blocks in an ExtendedBlockStorage section and write the result
     * into blockRefCount via reflection so that isEmpty() returns false for sections
     * that actually contain blocks.
     *
     * Iterating 4096 blocks per section is acceptable because copyFrom() is called once
     * per FakeChunk creation on a background executor thread, never on the main thread.
     */
    private static void recalcBlockRefCount(ExtendedBlockStorage storage) {
        if (BLOCK_REF_COUNT_FIELD == null) return;
        try {
            int count = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        IBlockState state = storage.get(x, y, z);
                        if (state != null && state.getBlock() != Blocks.AIR) {
                            count++;
                        }
                    }
                }
            }
            BLOCK_REF_COUNT_FIELD.set(storage, count);
        } catch (Exception e) {
            Bobby.LOGGER.debug("Bobby: failed to set blockRefCount on EBS", e);
        }
    }

    private final Map<BlockPos, TileEntity> frozenTileEntities = new HashMap<>();

    public FakeChunk(World world, int x, int z) {
        super(world, x, z);
    }

    public void copyFrom(Chunk source) {
        ExtendedBlockStorage[] src = source.getBlockStorageArray();
        ExtendedBlockStorage[] dst = this.getBlockStorageArray();

        for (int i = 0; i < Math.min(src.length, dst.length); i++) {
            if (src[i] != null && src[i] != Chunk.NULL_BLOCK_STORAGE) {
                dst[i] = src[i];
                /**
                 * FIX: Recalculate blockRefCount for every copied section.
                 *
                 * AnvilChunkLoader writes block data directly into BlockStateContainer's
                 * backing array, bypassing ExtendedBlockStorage.set() and leaving
                 * blockRefCount == 0. isEmpty() == (blockRefCount == 0), so the renderer's
                 * RenderChunk.rebuildChunk() skips every section and compiles no geometry.
                 * The chunk is then invisible at all distances, newly placed blocks are
                 * also invisible, and the issue persists even when standing inside the chunk.
                 */
                recalcBlockRefCount(dst[i]);
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

    /**
     * Belt-and-suspenders guard alongside the blockRefCount fix: even if
     * recalcBlockRefCount() ever fails silently, this ensures the renderer iterates
     * the correct section range rather than stopping at y=0.
     */
    @Override
    public int getTopFilledSegment() {
        ExtendedBlockStorage[] storageArrays = getBlockStorageArray();
        for (int i = storageArrays.length - 1; i >= 0; i--) {
            ExtendedBlockStorage storage = storageArrays[i];
            if (storage != null && storage != Chunk.NULL_BLOCK_STORAGE) {
                return storage.getYLocation();
            }
        }
        return 0;
    }

    @Override
    public void onTick(boolean skipRecheckGaps) { }

    @Override
    public void onLoad() { }

    @Override
    public void onUnload() {
        /**
         * FIX: previously didn't call super.onUnload(), so any entities left indexed in
         * this chunk's entityLists (see the addEntity/getEntityLists fix below) never got
         * handed to World.unloadEntities() on eviction - they'd end up indexed on a chunk
         * object nothing references anymore instead of being cleanly unloaded the normal
         * way.
         */
        super.onUnload();
        frozenTileEntities.clear();
    }

    @Override
    public IBlockState setBlockState(BlockPos pos, IBlockState state) {
        return getBlockState(pos);
    }

    @Override
    public void setModified(boolean modified) { }

    @Override
    public boolean needsSaving(boolean always) {
        return false;
    }

    @Override
    public void markDirty() { }

    @Override
    public void setLightFor(EnumSkyBlock type, BlockPos pos, int value) { }

    @Override
    public void generateSkylightMap() { }

    @Override
    public void checkLight() { }

    /**
     * FIX (mobs going permanently invisible after leaving and re-entering render range):
     * addEntity/removeEntity/removeEntityAtIndex/getEntityLists used to be no-ops here,
     * mirroring vanilla EmptyChunk (the shared blankChunk placeholder for "nothing here").
     * That's correct for EmptyChunk, a singleton nothing should ever really be positioned
     * in - but wrong for FakeChunk, which holds real deserialized terrain and, per design,
     * can legitimately have entities in it (frozen beyond simulationDistance, still visible
     * out to renderDistance thanks to EntityTrackerEntryMixin's extended tracking).
     *
     * With those overrides removed, every tick the world's entity-tick loop re-associates a
     * moving entity with whatever chunk is currently returned for its position, same as it
     * would for a real Chunk. When that position is a FakeChunk, the entity now actually
     * gets indexed into it via Chunk's own entityLists array (already initialized by
     * super(world, x, z)) instead of being silently dropped. Chunk-aware entity culling
     * (e.g. tr7zw's Entity Culling, commonly paired with Bobby, which needs a matching
     * OptiFine build to even run) reads exactly this per-chunk index to decide what's worth
     * drawing - so an entity that fell out of it stayed fully alive (hittable, audible,
     * still ticking) but never got rendered again, even once the player came back within
     * range, because that reindex only fires when the entity's own chunk coordinate
     * changes, not just because the player moved.
     */

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public void addTileEntity(TileEntity te) { }

    @Override
    public void addTileEntity(BlockPos pos, TileEntity te) { }

    @Override
    public void removeTileEntity(BlockPos pos) { }

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
    public boolean isLoaded() { return true; }

    @Override
    public boolean isPopulated() { return true; }

    @Override
    public boolean isTerrainPopulated() { return true; }

    @Override
    public void setTerrainPopulated(boolean terrainPopulated) { }

    @Override
    public boolean isLightPopulated() { return true; }

    @Override
    public void setLightPopulated(boolean lightPopulated) { }

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

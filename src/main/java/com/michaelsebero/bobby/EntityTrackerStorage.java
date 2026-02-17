package com.michaelsebero.bobby;

import net.minecraft.entity.Entity;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores original entity tracking ranges before Bobby modifies them.
 *
 * FIX: WeakHashMap is not thread-safe. storeInitValues is called from the integrated
 * server thread (EntityTrackerEntry constructor); getInitRange/getInitMaxRange may be
 * called from the client thread. Wrap both maps with Collections.synchronizedMap so
 * that all reads and writes are properly serialized.
 */
public class EntityTrackerStorage {
    private final Map<Entity, Integer> initRangeMap =
        Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Entity, Integer> initMaxRangeMap =
        Collections.synchronizedMap(new WeakHashMap<>());

    public void storeInitValues(Entity entity, int initRange, int initMaxRange) {
        initRangeMap.put(entity, initRange);
        initMaxRangeMap.put(entity, initMaxRange);
    }

    public Integer getInitRange(Entity entity) {
        return initRangeMap.getOrDefault(entity, null);
    }

    public Integer getInitMaxRange(Entity entity) {
        return initMaxRangeMap.getOrDefault(entity, null);
    }
}

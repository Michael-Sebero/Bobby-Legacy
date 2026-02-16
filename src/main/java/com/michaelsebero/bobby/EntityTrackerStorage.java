package com.michaelsebero.bobby;

import net.minecraft.entity.Entity;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores original entity tracking ranges before Bobby modifies them
 */
public class EntityTrackerStorage {
    private final Map<Entity, Integer> initRangeMap = new WeakHashMap<>();
    private final Map<Entity, Integer> initMaxRangeMap = new WeakHashMap<>();

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

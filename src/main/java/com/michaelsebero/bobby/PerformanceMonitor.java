package com.michaelsebero.bobby;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Utility class to monitor performance metrics for Bobby
 */
public class PerformanceMonitor {
    private static final int SAMPLE_SIZE = 100;
    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    
    private final ConcurrentLinkedQueue<Long> updateTimes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> loadTimes = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalChunksLoaded = new AtomicLong(0);
    private final AtomicLong totalChunksUnloaded = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    private PerformanceMonitor() {}
    
    public static PerformanceMonitor getInstance() {
        return INSTANCE;
    }
    
    public void recordUpdateTime(long timeNanos) {
        updateTimes.add(timeNanos);
        if (updateTimes.size() > SAMPLE_SIZE) {
            updateTimes.poll();
        }
    }
    
    public void recordLoadTime(long timeNanos) {
        loadTimes.add(timeNanos);
        if (loadTimes.size() > SAMPLE_SIZE) {
            loadTimes.poll();
        }
    }
    
    public void recordChunkLoaded() {
        totalChunksLoaded.incrementAndGet();
    }
    
    public void recordChunkUnloaded() {
        totalChunksUnloaded.incrementAndGet();
    }
    
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }
    
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }
    
    public double getAverageUpdateTimeMs() {
        if (updateTimes.isEmpty()) return 0;
        return updateTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0) / 1_000_000.0;
    }
    
    public double getAverageLoadTimeMs() {
        if (loadTimes.isEmpty()) return 0;
        return loadTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0) / 1_000_000.0;
    }
    
    public long getTotalChunksLoaded() {
        return totalChunksLoaded.get();
    }
    
    public long getTotalChunksUnloaded() {
        return totalChunksUnloaded.get();
    }
    
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        if (total == 0) return 0;
        return (double) hits / total * 100.0;
    }
    
    public String getFormattedStats() {
        return String.format(
            "Bobby Stats | Avg Update: %.2fms | Avg Load: %.2fms | " +
            "Loaded: %d | Unloaded: %d | Cache Hit: %.1f%%",
            getAverageUpdateTimeMs(),
            getAverageLoadTimeMs(),
            getTotalChunksLoaded(),
            getTotalChunksUnloaded(),
            getCacheHitRate()
        );
    }
    
    public void reset() {
        updateTimes.clear();
        loadTimes.clear();
        totalChunksLoaded.set(0);
        totalChunksUnloaded.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
    }
}

package com.michaelsebero.bobby;

import net.minecraftforge.common.config.Config;

@Config(modid = Bobby.MOD_ID)
public class BobbyConfig {
    
    @Config.Comment({
        "Enable/disable the entire mod",
        "Set to false to completely disable Bobby without uninstalling"
    })
    public static boolean enabled = true;
    
    @Config.Comment({
        "Do not load block entities (e.g. chests, furnaces, signs) in fake chunks.",
        "These entities need updating every tick which can significantly impact performance.",
        "Enabled by default because the render distance for block entities is usually",
        "smaller than the server view distance anyway.",
        "Disable only if you need to see chest contents at extreme distances."
    })
    public static boolean noBlockEntities = true;
    
    @Config.Comment({
        "Delays the unloading of chunks which are outside your view distance.",
        "Prevents having to reload chunks when briefly leaving an area (e.g. cutscenes, teleports).",
        "Does not work across dimensions - chunks are immediately unloaded when changing dimensions.",
        "Set to 0 to disable delay and unload chunks immediately.",
        "Recommended: 60 seconds for most use cases"
    })
    @Config.RangeInt(min = 0, max = 300)
    public static int unloadDelaySecs = 60;

    @Config.Comment({
        "Maximum render distance in chunks.",
        "This value is synchronized with the in-game Video Settings slider.",
        "Adjust in Video Settings > Render Distance or edit this config directly.",
        "",
        "WARNING: Higher values dramatically increase RAM and VRAM usage!",
        "Memory usage roughly follows: (render_distance * 2)² * 5MB",
        "",
        "Recommended values based on system specs:",
        "  - Low-end   (4-8GB RAM):   16-32 chunks",
        "  - Mid-range (8-16GB RAM):  32-64 chunks", 
        "  - High-end  (16GB+ RAM):   64-128 chunks",
        "  - Extreme   (32GB+ RAM):   128-256 chunks",
        "",
        "Values above 256 are experimental and may cause instability!",
        "Always monitor your memory usage when increasing this value."
    })
    @Config.RangeInt(min = 2, max = 1816)
    public static int maxRenderDistance = 32;
    
    @Config.Comment({
        "Overwrites the view distance of the integrated server (singleplayer).",
        "This allows Bobby to be useful in singleplayer by loading chunks beyond the server's view distance.",
        "Set to 0 to disable and use the default render distance.",
        "Bobby is only active in singleplayer when this is enabled.",
        "Requires world reload to take effect.",
        "",
        "Example: If you set this to 8 and maxRenderDistance to 32,",
        "the server will load chunks up to 8 chunks away (normally visible),",
        "and Bobby will cache and display chunks from 8-32 chunks away."
    })
    @Config.RangeInt(min = 0, max = 32)
    public static int viewDistanceOverwrite = 0;
    
    @Config.RequiresMcRestart
    @Config.Comment({
        "Maximum number of chunks to load per game tick (1/20th of a second).",
        "Lower values reduce lag spikes but slow down chunk loading.",
        "Higher values load chunks faster but may cause stuttering.",
        "",
        "Recommended: 4-8 for most systems, 2-4 for low-end systems",
        "Increase if you have a fast SSD and powerful CPU."
    })
    @Config.RangeInt(min = 1, max = 32)
    public static int maxChunksPerTick = 4;
    
    @Config.RequiresMcRestart
    @Config.Comment({
        "Number of threads to use for chunk loading operations.",
        "More threads = faster parallel loading but more CPU usage.",
        "Should generally be set to half your CPU core count.",
        "",
        "Recommended values:",
        "  - Dual-core:  2 threads",
        "  - Quad-core:  2-4 threads",
        "  - 6-8 cores:  4-6 threads",
        "  - 8+ cores:   6-8 threads",
        "",
        "Going above 8 threads rarely provides benefits."
    })
    @Config.RangeInt(min = 1, max = 16)
    public static int loadingThreads = 4;
    
    @Config.Comment({
        "Maximum size of the chunk NBT tag cache.",
        "Larger cache = less disk I/O but more RAM usage.",
        "Each cached chunk uses approximately 50-100KB of RAM.",
        "",
        "Memory usage estimate: maxCacheSize * 75KB",
        "  - 256:  ~19MB",
        "  - 512:  ~38MB",
        "  - 1024: ~77MB",
        "  - 2048: ~154MB",
        "",
        "Recommended: 512-1024 for most systems"
    })
    @Config.RangeInt(min = 64, max = 4096)
    public static int maxCacheSize = 512;
    
    @Config.Comment({
        "Enable asynchronous chunk saving.",
        "When enabled, chunk saves are queued and processed in the background.",
        "This reduces lag when chunks are saved but uses more CPU.",
        "Disable if you experience issues with chunk corruption or data loss.",
        "",
        "Recommended: true for most systems"
    })
    public static boolean asyncSaving = true;
    
    @Config.Comment({
        "How often (in seconds) to automatically save cached chunks to disk.",
        "Lower values = more frequent saves, less data loss on crash but more I/O.",
        "Higher values = less frequent saves, more data loss risk but better performance.",
        "",
        "Set to a very high value if you have an SSD and want maximum performance.",
        "Set to a lower value if you have an HDD or want maximum data safety.",
        "",
        "Recommended:",
        "  - SSD: 60-120 seconds",
        "  - HDD: 30-60 seconds",
        "  - Safety-focused: 10-30 seconds"
    })
    @Config.RangeInt(min = 10, max = 600)
    public static int autoSaveInterval = 60;
    
    @Config.Comment({
        "Enable debug logging for Bobby operations.",
        "Useful for troubleshooting but may spam the log file.",
        "Leave disabled unless you're experiencing issues."
    })
    public static boolean debugLogging = false;
    
    @Config.Comment({
        "Enable performance monitoring and statistics.",
        "Adds minimal overhead but provides useful information in F3 debug screen.",
        "Statistics include: chunk load times, cache hit rate, memory usage."
    })
    public static boolean enablePerformanceMonitoring = true;
    
    /**
     * Validates configuration values and corrects invalid ones
     */
    public static void validate() {
        // Ensure render distance is within reasonable bounds
        if (maxRenderDistance < 2) {
            System.err.println("[Bobby] maxRenderDistance too low, setting to 2");
            maxRenderDistance = 2;
        }
        if (maxRenderDistance > 1816) {
            System.err.println("[Bobby] maxRenderDistance too high, setting to 1816");
            maxRenderDistance = 1816;
        }
        
        // Warn about extreme render distances
        if (maxRenderDistance > 256) {
            System.out.println("[Bobby] WARNING: Render distance above 256 may cause severe performance issues!");
            System.out.println("[Bobby] Estimated memory usage: ~" + estimateMemoryUsage(maxRenderDistance) + "MB");
        }
        
        // Ensure loading threads is reasonable
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        if (loadingThreads > availableProcessors) {
            System.out.println("[Bobby] WARNING: loadingThreads (" + loadingThreads + 
                             ") exceeds available processors (" + availableProcessors + ")");
        }
        
        // Ensure chunks per tick isn't too high
        if (maxChunksPerTick > 16) {
            System.out.println("[Bobby] WARNING: maxChunksPerTick is very high, may cause lag spikes");
        }
    }
    
    /**
     * Estimates memory usage in MB for a given render distance
     */
    private static long estimateMemoryUsage(int renderDistance) {
        // Rough estimate: (diameter)² chunks * 5MB per chunk
        long diameter = (renderDistance * 2L);
        long totalChunks = diameter * diameter;
        return (totalChunks * 5) / 1024; // Convert KB to MB
    }
    
    /**
     * Returns a preset configuration for low-end systems
     */
    public static void applyLowEndPreset() {
        maxRenderDistance = 24;
        maxChunksPerTick = 2;
        loadingThreads = 2;
        maxCacheSize = 256;
        asyncSaving = true;
        autoSaveInterval = 30;
        System.out.println("[Bobby] Applied low-end system preset");
    }
    
    /**
     * Returns a preset configuration for high-end systems
     */
    public static void applyHighEndPreset() {
        maxRenderDistance = 128;
        maxChunksPerTick = 8;
        loadingThreads = 6;
        maxCacheSize = 2048;
        asyncSaving = true;
        autoSaveInterval = 120;
        System.out.println("[Bobby] Applied high-end system preset");
    }
}

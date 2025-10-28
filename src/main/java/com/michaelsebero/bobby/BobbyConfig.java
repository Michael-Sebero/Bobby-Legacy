package com.michaelsebero.bobby;

import net.minecraftforge.common.config.Config;

@Config(modid = Bobby.MOD_ID)
public class BobbyConfig {
    @Config.Comment("Enable/disable the entire mod")
    public static boolean enabled = true;
    
    @Config.Comment("Do not load block entities (e.g. chests) in fake chunks.\n" +
            "These need updating every tick which can add up.\n" +
            "Enabled by default because the render distance for block entities is usually smaller than the server-view distance anyway.")
    public static boolean noBlockEntities = true;
    
    @Config.Comment("Delays the unloading of chunks which are outside your view distance.\n" +
            "Saves you from having to reload all chunks when leaving the area for a short moment (e.g. cut scenes).\n" +
            "Does not work across dimensions.")
    @Config.RangeInt(min = 0, max = 300)
    public static int unloadDelaySecs = 60;

    @Config.Comment("Changes the maximum value configurable for Render Distance.\n" +
            "Requires Sodium/Vintagium.")
    @Config.RangeInt(min = 2, max = 64)
    public static int maxRenderDistance = 32;
    
    @Config.Comment("Overwrites the view-distance of the integrated server.\n" +
            "This allows Bobby to be useful in Singleplayer.\n" +
            "Disabled when at 0.\n" +
            "Bobby is active in singleplayer only if this is enabled.\n" +
            "Requires re-log to en-/disable.")
    @Config.RangeInt(min = 0, max = 32)
    public static int viewDistanceOverwrite = 0;
    
    // Performance optimization settings
    @Config.Comment("Maximum number of chunks to load per game tick.\n" +
            "Lower values reduce lag spikes but slow down chunk loading.\n" +
            "Higher values load chunks faster but may cause stuttering.")
    @Config.RangeInt(min = 1, max = 16)
    public static int maxChunksPerTick = 4;
    
    @Config.Comment("Number of threads to use for chunk loading.\n" +
            "More threads = faster loading but more CPU usage.\n" +
            "Recommended: 2-4 for most systems.")
    @Config.RangeInt(min = 1, max = 16)
    public static int loadingThreads = 4;
    
    @Config.Comment("Maximum size of the chunk tag cache.\n" +
            "Larger cache = less disk I/O but more RAM usage.\n" +
            "Each cached chunk uses approximately 50-100KB of RAM.")
    @Config.RangeInt(min = 64, max = 2048)
    public static int maxCacheSize = 512;
    
    @Config.Comment("Enable asynchronous chunk saving.\n" +
            "Reduces lag when chunks are saved but may use more CPU.")
    public static boolean asyncSaving = true;
    
    @Config.Comment("How often (in seconds) to automatically save cached chunks to disk.\n" +
            "Lower values = more frequent saves, less data loss on crash but more I/O.")
    @Config.RangeInt(min = 10, max = 600)
    public static int autoSaveInterval = 60;
}

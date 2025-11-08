package com.michaelsebero.bobby;

import net.minecraftforge.common.config.Config;

@Config(modid = Bobby.MOD_ID)
public class BobbyConfig {
    
    @Config.Comment("Enable/disable Bobby")
    public static boolean enabled = true;
    
    @Config.Comment({
        "Maximum render distance in chunks (2-1816)",
        "Synced with Video Settings slider"
    })
    @Config.RangeInt(min = 2, max = 1816)
    public static int renderDistance = 32;
    
    @Config.Comment({
        "Simulation distance - chunks within this radius get full server-side updates",
        "In singleplayer: Controls how far the integrated server loads chunks",
        "In multiplayer: Chunks beyond server's distance become fake chunks",
        "Real chunks (0 to simulationDistance): Full ticking, entities, updates",
        "Fake chunks (simulationDistance to renderDistance): Visual only, frozen",
        "Requires world reload to take effect in singleplayer"
    })
    @Config.RangeInt(min = 2, max = 32)
    public static int simulationDistance = 8;
    
    @Config.Comment("Skip tile entities in fake chunks for better performance")
    public static boolean skipTileEntities = true;
    
    @Config.RequiresMcRestart
    @Config.Comment("Chunks to process per tick")
    @Config.RangeInt(min = 1, max = 16)
    public static int chunksPerTick = 2;
    
    @Config.RequiresMcRestart
    @Config.Comment("Background loading threads")
    @Config.RangeInt(min = 1, max = 16)
    public static int loadThreads = 3;
    
    @Config.Comment("In-memory chunk cache size")
    @Config.RangeInt(min = 64, max = 8192)
    public static int cacheSize = 1024;
    
    @Config.Comment("Auto-save interval (seconds)")
    @Config.RangeInt(min = 30, max = 600)
    public static int saveInterval = 120;
    
    @Config.Comment("Show debug info in F3 screen")
    public static boolean showDebug = true;
    
    public static void validate() {
        renderDistance = Math.max(2, Math.min(renderDistance, 1816));
        simulationDistance = Math.max(2, Math.min(simulationDistance, renderDistance));
        
        if (renderDistance > 256) {
            Bobby.LOGGER.warn("High render distance may impact performance");
        }
    }
}

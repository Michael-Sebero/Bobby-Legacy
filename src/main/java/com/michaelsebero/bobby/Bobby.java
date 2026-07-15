package com.michaelsebero.bobby;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

@Mod(modid = Bobby.MOD_ID, name = Bobby.MOD_NAME, version = Bobby.VERSION, clientSideOnly = true)
public class Bobby {
    public static final String MOD_ID = "bobby";
    public static final String MOD_NAME = "Bobby";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @Mod.Instance(MOD_ID)
    public static Bobby INSTANCE;
    
    // Storage for entity tracking original values
    public final EntityTrackerStorage entityStorage = new EntityTrackerStorage();

    @Mod.EventHandler
    public void preinit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        // Freezes distant living entities beyond simulationDistance. Registered here
        // (not via Mixin) because it hooks LivingUpdateEvent - see EntityFreezeHandler
        // for why this replaced the old EntityTickingMixin.
        MinecraftForge.EVENT_BUS.register(new EntityFreezeHandler());
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(MOD_ID)) {
            ConfigManager.sync(MOD_ID, Config.Type.INSTANCE);
        }
    }
}

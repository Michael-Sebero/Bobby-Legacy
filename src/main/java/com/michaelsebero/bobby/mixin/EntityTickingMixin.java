package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents entities beyond simulation distance from ticking
 * Entities between simulationDistance and renderDistance are visible but frozen
 * This mirrors the behavior of fake chunks - visual only, no updates
 */
@Mixin(Entity.class)
public abstract class EntityTickingMixin {
    
    @Shadow public World world;
    @Shadow public double posX;
    @Shadow public double posY;
    @Shadow public double posZ;
    @Shadow public float rotationYaw;
    @Shadow public float rotationPitch;
    @Shadow public float prevRotationYaw;
    @Shadow public float prevRotationPitch;
    @Shadow public int ticksExisted;
    
    @Inject(method = "onUpdate", at = @At("HEAD"), cancellable = true)
    private void freezeDistantEntities(CallbackInfo ci) {
        if (!BobbyConfig.enabled || !BobbyConfig.freezeDistantEntities) {
            return;
        }
        
        Entity self = (Entity) (Object) this;
        
        // Players should always update
        if (self instanceof EntityPlayer) {
            return;
        }
        
        // Only freeze entities that have existed for at least 1 tick
        // This allows newly spawned entities to initialize properly
        if (ticksExisted < 1) {
            return;
        }
        
        // Only apply to integrated server (singleplayer)
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) {
            return;
        }
        
        // Find nearest player
        EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(self, -1.0);
        if (nearestPlayer == null) {
            return;
        }
        
        // Calculate distance in chunks
        double dx = posX - nearestPlayer.posX;
        double dz = posZ - nearestPlayer.posZ;
        double distanceChunks = Math.sqrt(dx * dx + dz * dz) / 16.0;
        
        // If beyond simulation distance, freeze the entity
        if (distanceChunks > BobbyConfig.simulationDistance) {
            // Freeze rotation by syncing current rotation to previous rotation
            // This prevents interpolation/spinning on client
            prevRotationYaw = rotationYaw;
            prevRotationPitch = rotationPitch;
            
            ci.cancel(); // Don't update this entity - it's frozen
        }
    }
}

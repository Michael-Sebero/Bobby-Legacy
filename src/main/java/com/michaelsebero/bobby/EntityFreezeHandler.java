package com.michaelsebero.bobby;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Freezes living entities (mobs, animals) beyond simulationDistance so they stop
 * ticking AI/movement, per BobbyConfig.freezeDistantEntities.
 *
 * REPLACES the old mixin/EntityTickingMixin.java, which injected into
 * Entity.onUpdate() and cancelled it there. That didn't actually work: onUpdate()
 * is overridden at every level of the living-entity hierarchy (EntityLivingBase,
 * EntityLiving, EntityMob/EntityAnimal, etc.), so Java dispatch calls the
 * most-derived override directly, bypassing Entity's own onUpdate() body (where the
 * mixin's injected code lived) for almost every entity type that actually has AI.
 * Even on the path where a subclass's onUpdate() does call super.onUpdate(),
 * cancelling that call only unwinds that one frame - it doesn't stop the
 * subclass's own subsequent statements (AI tasks, movement, onLivingUpdate()) from
 * running anyway.
 *
 * LivingUpdateEvent is fired by Forge from inside EntityLivingBase's own update
 * logic specifically so mods have a reliable, cancelable "skip this tick entirely"
 * hook for living entities, regardless of how many subclasses override onUpdate()
 * beneath it.
 *
 * Scope note: this only covers EntityLivingBase subtypes (mobs, animals - the
 * entities with actual AI/pathfinding cost). Non-living entities (dropped items,
 * projectiles, XP orbs) have much cheaper update logic and were never really the
 * point of this feature, so they're intentionally left alone here, same as before.
 *
 * Registered manually in Bobby.preinit() via MinecraftForge.EVENT_BUS.register(...).
 * This is a plain event handler, not a mixin, so it must NOT be listed in
 * mixins.bobby.json - remove the old EntityTickingMixin entry from that file and
 * delete mixin/EntityTickingMixin.java when wiring this in.
 */
public class EntityFreezeHandler {

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!BobbyConfig.enabled || !BobbyConfig.freezeDistantEntities) {
            return;
        }

        EntityLivingBase entity = event.getEntityLiving();

        if (entity instanceof EntityPlayer) {
            return;
        }

        if (entity.ticksExisted < 1) {
            return;
        }

        // Only apply to integrated server (singleplayer)
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) {
            return;
        }

        EntityPlayer nearestPlayer = entity.world.getClosestPlayerToEntity(entity, -1.0);
        if (nearestPlayer == null) {
            return;
        }

        double dx = entity.posX - nearestPlayer.posX;
        double dz = entity.posZ - nearestPlayer.posZ;

        // Compare squared distances to avoid a sqrt() call for every living entity, every tick.
        double simDistBlocks = BobbyConfig.simulationDistance * 16.0;
        double distanceSq = dx * dx + dz * dz;

        if (distanceSq > simDistBlocks * simDistBlocks) {
            // Freeze rotation to prevent visual interpolation/spinning while frozen
            entity.prevRotationYaw = entity.rotationYaw;
            entity.prevRotationPitch = entity.rotationPitch;
            event.setCanceled(true);
        }
    }
}

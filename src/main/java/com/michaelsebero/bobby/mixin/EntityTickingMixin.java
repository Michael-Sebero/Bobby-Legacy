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
 * Prevents entities beyond simulation distance from ticking.
 * Entities between simulationDistance and renderDistance are visible but frozen.
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

        if (self instanceof EntityPlayer) {
            return;
        }

        if (ticksExisted < 1) {
            return;
        }

        // Only apply to integrated server (singleplayer)
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) {
            return;
        }

        EntityPlayer nearestPlayer = world.getClosestPlayerToEntity(self, -1.0);
        if (nearestPlayer == null) {
            return;
        }

        double dx = posX - nearestPlayer.posX;
        double dz = posZ - nearestPlayer.posZ;

        /**
         * FIX (Bug 7): Original computed Math.sqrt(dx*dx + dz*dz) / 16.0 and compared to
         * simulationDistance. sqrt() is expensive when called for every non-player entity
         * every tick. Compare squared distances instead — equivalent math, zero sqrt cost.
         *
         * (dist/16 > simDist)  ≡  dist² > (simDist * 16)²
         */
        double simDistBlocks = BobbyConfig.simulationDistance * 16.0;
        double distanceSq = dx * dx + dz * dz;

        if (distanceSq > simDistBlocks * simDistBlocks) {
            // Freeze rotation to prevent visual interpolation/spinning while frozen
            prevRotationYaw = rotationYaw;
            prevRotationPitch = rotationPitch;

            ci.cancel();
        }
    }
}

package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends entity render distance to match Bobby's render distance.
 */
@Mixin(Entity.class)
public abstract class EntityRenderDistanceMixin {

    /**
     * renderDistanceWeight is private on Entity, so it needs @Shadow rather than a
     * direct read off an Entity-typed reference (see the javac "has private access"
     * error this replaced). It also has to be shadowed as static: Mixin's own field
     * validator rejects a non-static shadow here with "STATIC modifier of @Shadow
     * field ... does not match the target", meaning the real field in this Forge/MCP
     * build is a private static double, not a per-instance one.
     */
    @Shadow private static double renderDistanceWeight;

    @Inject(method = "isInRangeToRenderDist", at = @At("HEAD"), cancellable = true)
    public void extendRenderDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!BobbyConfig.enabled) {
            return;
        }

        Entity self = (Entity) (Object) this;

        double d0 = self.getEntityBoundingBox().getAverageEdgeLength();

        if (Double.isNaN(d0)) {
            d0 = 1.0D;
        }

        int vanillaMaxDistance = 32;
        double multiplier = Math.max(1.0, (double) BobbyConfig.renderDistance / vanillaMaxDistance);

        d0 = d0 * 64.0D * multiplier * renderDistanceWeight;

        cir.setReturnValue(distance < d0 * d0);
    }
}

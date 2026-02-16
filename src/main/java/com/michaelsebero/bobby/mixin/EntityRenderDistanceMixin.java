package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends entity render distance to match Bobby's render distance
 * Entities will be visible up to the configured render distance
 */
@Mixin(Entity.class)
public abstract class EntityRenderDistanceMixin {
    @Shadow
    private static double renderDistanceWeight = 1.0D;
    
    @Shadow
    public abstract AxisAlignedBB getEntityBoundingBox();

    @Inject(method = "isInRangeToRenderDist", at = @At("HEAD"), cancellable = true)
    public void extendRenderDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!BobbyConfig.enabled) {
            return;
        }
        
        double d0 = getEntityBoundingBox().getAverageEdgeLength();

        if (Double.isNaN(d0)) {
            d0 = 1.0D;
        }

        // Calculate multiplier based on render distance vs vanilla max (32 chunks)
        // This scales entity render distance proportionally to chunk render distance
        int vanillaMaxDistance = 32;
        double multiplier = Math.max(1.0, (double) BobbyConfig.renderDistance / vanillaMaxDistance);
        
        d0 = d0 * 64.0D * multiplier * renderDistanceWeight;

        boolean result = distance < d0 * d0;

        cir.setReturnValue(result);
        cir.cancel();
    }
}

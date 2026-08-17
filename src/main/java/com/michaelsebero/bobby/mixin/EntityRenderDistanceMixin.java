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
     * renderDistanceWeight is a private instance field on Entity (each entity type,
     * e.g. fireworks, sets its own value in its constructor). It can't be read off a
     * plain Entity-typed reference from outside the class - javac checks that access
     * against the real target field and rejects it ("renderDistanceWeight has private
     * access in Entity"). Like every other private target field this mod touches
     * (blankChunk, playerViewRadius, range/maxRange, ...), it needs @Shadow so the
     * field lives on this mixin class and this.renderDistanceWeight is a same-class
     * access that Mixin merges onto the real field at class-load time.
     */
    @Shadow private double renderDistanceWeight;

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

        d0 = d0 * 64.0D * multiplier * this.renderDistanceWeight;

        cir.setReturnValue(distance < d0 * d0);
    }
}

package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.Bobby;
import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extends entity tracking distance on the server to match Bobby's render distance
 * This allows the server to send entity data for entities up to the render distance
 * Only affects integrated server (singleplayer)
 */
@Mixin(EntityTrackerEntry.class)
public class EntityTrackerEntryMixin {
    @Shadow @Final @Mutable private int range;
    @Shadow @Final @Mutable private int maxRange;
    @Shadow @Final private Entity trackedEntity;
    
    @Inject(method = "<init>", at = @At("RETURN"))
    private void extendTrackingDistance(Entity entityIn, int rangeIn, int maxRangeIn, int updateFrequencyIn, boolean sendVelocityUpdatesIn, CallbackInfo ci) {
        if (!BobbyConfig.enabled) {
            return;
        }
        
        // Store original values for potential restoration
        Bobby.INSTANCE.entityStorage.storeInitValues(entityIn, rangeIn, maxRangeIn);
        
        // Calculate multiplier based on render distance
        // Vanilla max tracking is typically tied to 32 chunk view distance
        int vanillaMaxDistance = 32;
        double multiplier = Math.max(1.0, (double) BobbyConfig.renderDistance / vanillaMaxDistance);
        
        // Extend tracking range to match render distance
        this.range = (int) (rangeIn * multiplier);
        this.maxRange = (int) (maxRangeIn * multiplier);
    }
}

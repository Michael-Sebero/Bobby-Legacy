package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Extends entity render distance to match Bobby's render distance.
 */
@Mixin(Entity.class)
public abstract class EntityRenderDistanceMixin {

    /**
     * FIX: The previous version declared renderDistanceWeight as a @Shadow static field.
     * Without a refMap loaded (CleanroomMC early-mixin environment), Mixin cannot resolve
     * the MCP name to the SRG/obf name and throws InvalidMixinException at launch, crashing
     * before the game even starts. This is the same class of bug that was previously fixed
     * for the getEntityBoundingBox() @Shadow abstract method.
     *
     * Fix: drop the @Shadow entirely. Read the static field once via reflection at class
     * load time and cache it in RENDER_DISTANCE_WEIGHT. If reflection fails for any reason,
     * fall back to 1.0 (the vanilla default), which gives correct base behaviour.
     *
     * The inject body already casts (Object)this to Entity for the bounding box call, so
     * no other @Shadow declarations are needed here.
     */
    private static final double RENDER_DISTANCE_WEIGHT = resolveRenderDistanceWeight();

    private static double resolveRenderDistanceWeight() {
        // Try every declared field on Entity; the one named "renderDistanceWeight" (MCP)
        // or its SRG equivalent is a public static double. We identify it by type and
        // by name-suffix to be robust against both mapped and unmapped environments.
        try {
            for (Field f : Entity.class.getDeclaredFields()) {
                if (f.getType() == double.class
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getName().contains("renderDistanceWeight")) {
                    f.setAccessible(true);
                    return f.getDouble(null);
                }
            }
            // Fallback: scan for any public static double on Entity — vanilla only has one.
            for (Field f : Entity.class.getFields()) {
                if (f.getType() == double.class
                        && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f.getDouble(null);
                }
            }
        } catch (Exception e) {
            // Reflection failed — use the vanilla default.
        }
        return 1.0;
    }

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

        d0 = d0 * 64.0D * multiplier * RENDER_DISTANCE_WEIGHT;

        cir.setReturnValue(distance < d0 * d0);
    }
}

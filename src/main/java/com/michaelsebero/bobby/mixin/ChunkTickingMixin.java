package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.FakeChunk;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensure fake chunks are completely inert - never ticked, never updated
 * This is the main performance optimization for Bobby
 */
@Mixin(Chunk.class)
public class ChunkTickingMixin {
    
    /**
     * Prevent fake chunks from ever being ticked
     */
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true)
    private void preventFakeChunkTick(boolean skipRecheckGaps, CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        if (self instanceof FakeChunk) {
            ci.cancel(); // Do absolutely nothing
        }
    }
    
    /**
     * Fake chunks never need saving
     */
    @Inject(method = "needsSaving", at = @At("HEAD"), cancellable = true)
    private void fakeChunksNeverSave(boolean always, CallbackInfoReturnable<Boolean> cir) {
        Chunk self = (Chunk) (Object) this;
        if (self instanceof FakeChunk) {
            cir.setReturnValue(false);
        }
    }
    
    /**
     * Prevent fake chunks from having their light calculated
     */
    @Inject(method = "checkLight", at = @At("HEAD"), cancellable = true)
    private void preventFakeLightCheck(CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        if (self instanceof FakeChunk) {
            ci.cancel();
        }
    }
    
    /**
     * Prevent fake chunks from generating skylight
     */
    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true)
    private void preventFakeSkylightGen(CallbackInfo ci) {
        Chunk self = (Chunk) (Object) this;
        if (self instanceof FakeChunk) {
            ci.cancel();
        }
    }
}

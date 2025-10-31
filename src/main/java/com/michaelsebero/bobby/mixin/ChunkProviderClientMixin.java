package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import com.michaelsebero.bobby.FakeChunkManager;
import com.michaelsebero.bobby.FakeChunkStorage;
import com.michaelsebero.bobby.ext.IChunkProviderClient;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(ChunkProviderClient.class)
public abstract class ChunkProviderClientMixin implements IChunkProviderClient {
    @Shadow public abstract Chunk getLoadedChunk(int x, int z);
    @Shadow @Final private Chunk blankChunk;
    @Shadow @Final private World world;
    @Nullable
    protected FakeChunkManager bobbyChunkManager = null;
    protected @Nullable NBTTagCompound bobbyChunkReplacement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bobbyInit(World worldIn, CallbackInfo ci) {
        if(BobbyConfig.enabled)
            bobbyChunkManager = new FakeChunkManager((WorldClient)worldIn, (ChunkProviderClient) (Object) this);
    }

    @Nullable
    @Override
    public FakeChunkManager getBobbyChunkManager() {
        return bobbyChunkManager;
    }

    @Inject(method = "provideChunk", at = @At("RETURN"), cancellable = true)
    private void bobbyGetChunk(int x, int z, CallbackInfoReturnable<Chunk> ci) {
        if (ci.getReturnValue() != blankChunk) {
            return;
        }

        if (bobbyChunkManager == null) {
            return;
        }

        Chunk chunk = bobbyChunkManager.getChunk(x, z);
        if (chunk != null) {
            ci.setReturnValue(chunk);
        }
    }

    @Inject(method = "loadChunk", at = @At("HEAD"))
    private void bobbyUnloadFakeChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        bobbyChunkManager.unload(x, z, true);
    }

    @Inject(method = "unloadChunk", at = @At("HEAD"))
    private void bobbySaveChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        FakeChunkStorage storage = bobbyChunkManager.getStorage();
        NBTTagCompound tag = storage.serialize(chunk);
        storage.save(chunk.getPos(), tag);
        bobbyChunkReplacement = tag;
    }

    @Inject(method = "unloadChunk", at = @At("RETURN"))
    private void bobbyReplaceChunk(int chunkX, int chunkZ, CallbackInfo ci) {
        if (bobbyChunkManager == null) {
            return;
        }

        NBTTagCompound tag = bobbyChunkReplacement;
        bobbyChunkReplacement = null;
        if (tag == null) {
            return;
        }
        bobbyChunkManager.load(chunkX, chunkZ, tag, bobbyChunkManager.getStorage());
    }

    @Inject(method = "makeString", at = @At("RETURN"), cancellable = true)
    private void bobbyDebugString(CallbackInfoReturnable<String> cir) {
        if (bobbyChunkManager == null) {
            return;
        }

        cir.setReturnValue(cir.getReturnValue() + " " + bobbyChunkManager.getDebugString());
    }
}

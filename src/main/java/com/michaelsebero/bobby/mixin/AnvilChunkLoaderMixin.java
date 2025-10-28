package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.ext.AnvilChunkLoaderExt;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AnvilChunkLoader.class)
public class AnvilChunkLoaderMixin implements AnvilChunkLoaderExt {
    private boolean bobby$loadTes = true;

    @Override
    public void bobby$setLoadsTileEntities(boolean b) {
        bobby$loadTes = b;
    }

    @Redirect(method = "loadEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NBTTagCompound;getTagList(Ljava/lang/String;I)Lnet/minecraft/nbt/NBTTagList;"))
    private NBTTagList getTagList(NBTTagCompound compound, String key, int type) {
        if(!bobby$loadTes) {
            if(key.equals("TileEntities") || key.equals("TileTicks")) {
                return new NBTTagList();
            }
        }
        return compound.getTagList(key, type);
    }
}

package com.michaelsebero.bobby.mixin;

import com.michaelsebero.bobby.BobbyConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AnvilChunkLoader.class)
public class AnvilChunkLoaderMixin {
    
    @Redirect(method = "loadEntities", at = @At(value = "INVOKE", 
        target = "Lnet/minecraft/nbt/NBTTagCompound;getTagList(Ljava/lang/String;I)Lnet/minecraft/nbt/NBTTagList;"))
    private NBTTagList skipTileEntities(NBTTagCompound compound, String key, int type) {
        if (BobbyConfig.skipTileEntities) {
            if (key.equals("TileEntities") || key.equals("TileTicks")) {
                return new NBTTagList();
            }
        }
        return compound.getTagList(key, type);
    }
}
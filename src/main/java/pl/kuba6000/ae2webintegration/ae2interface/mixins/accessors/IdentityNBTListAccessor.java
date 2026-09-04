package pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors;

import java.util.List;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NBTTagList.class)
public interface IdentityNBTListAccessor {

    @Accessor("tagList")
    List<NBTBase> web$getIdentityElements();
}

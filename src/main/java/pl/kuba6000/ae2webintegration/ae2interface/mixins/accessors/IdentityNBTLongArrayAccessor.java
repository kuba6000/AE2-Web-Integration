package pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors;

import net.minecraft.nbt.NBTTagLongArray;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NBTTagLongArray.class)
public interface IdentityNBTLongArrayAccessor {

    @Accessor("data")
    long[] web$getIdentityData();
}

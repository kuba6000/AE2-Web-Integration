package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;

import appeng.api.stacks.AEKey;
import appeng.me.Grid;

@Mixin(value = AEKey.class, remap = false)
public abstract class AEItemMixin implements IAEKey {

    @Shadow
    public ResourceLocation getId() {
        throw new UnsupportedOperationException("Mixin failed to apply");
    }

    @Shadow
    public Component getDisplayName() {
        throw new UnsupportedOperationException("Mixin failed to apply");
    }

    @Override
    public String web$getItemID() {
        ResourceLocation rs = getId();
        return rs.getNamespace() + ":" + rs.getPath();
    }

    @Override
    public String web$getDisplayName() {
        return getDisplayName().getString();
    }

    // @Override
    // public default long web$getStackSize() {
    // return getStackSize();
    // }
    //
    @Override
    public boolean web$isCraftable(IAEGrid grid) {
        return ((Grid) grid).getCraftingService()
            .isCraftable((AEKey) (Object) this);
    }
    //
    // @Override
    // public default long web$getCountRequestable() {
    // return getCountRequestable();
    // }
    //
    // @Override
    // public default long web$getCountRequestableCrafts() {
    // return getCountRequestableCrafts();
    // }
    //
    // @Override
    // public default void web$reset() {
    // reset();
    // }

    @Override
    public boolean web$isSameType(IAEKey other) {
        return this.equals(other);
    }

    // @Override
    // public default IAEKey web$copy() {
    // return (IAEKey) copy();
    // }
    //
    // @Override
    // public default void web$setStackSize(long size) {
    // setStackSize(size);
    // }
}

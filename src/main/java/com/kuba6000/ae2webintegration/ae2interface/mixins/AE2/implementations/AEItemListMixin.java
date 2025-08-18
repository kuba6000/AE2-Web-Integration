package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

@Mixin(value = KeyCounter.class, remap = false)
public abstract class AEItemListMixin implements com.kuba6000.ae2webintegration.core.interfaces.IItemList {

    @Shadow
    public long get(AEKey key) {
        throw new UnsupportedOperationException("Mixin method not implemented");
    }

    @Override
    public long web$findPrecise(IAEKey stack) {
        return get((AEKey) stack);
    }

}

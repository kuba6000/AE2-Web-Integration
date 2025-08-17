package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

@Mixin(value = GenericStack.class, remap = false)
public class AEGenericStackMixin implements IAEGenericStack {

    @Shadow
    @Final
    private AEKey what;

    @Shadow
    @Final
    private long amount;

    @Override
    public IAEKey web$what() {
        return (IAEKey) what;
    }

    @Override
    public long web$amount() {
        return amount;
    }

    @Override
    public IAEGenericStack web$copy() {
        return (IAEGenericStack) (Object) new GenericStack(this.what, this.amount);
    }
}

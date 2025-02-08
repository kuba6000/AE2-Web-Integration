package com.kuba6000.ae2webintegration.ae2interface.implementations;

import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

import appeng.api.storage.data.IAEItemStack;

public class ItemStack implements IItemStack {

    @GSONUtils.SkipGSON
    public IAEItemStack stack;

    public ItemStack(IAEItemStack stack) {
        this.stack = stack;
    }

    @Override
    public int hashCode() {
        return stack.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ItemStack && ((ItemStack) obj).stack.equals(stack);
    }

    @Override
    public String getItemID() {
        return stack.getItem()
            .getRegistryName() + ":"
            + stack.getItemDamage();
    }

    @Override
    public String getDisplayName() {
        return stack.asItemStackRepresentation()
            .getDisplayName();
    }

    @Override
    public long getStackSize() {
        return stack.getStackSize();
    }

    @Override
    public boolean isCraftable() {
        return stack.isCraftable();
    }

    @Override
    public long getCountRequestable() {
        return stack.getCountRequestable();
    }

    @Override
    public long getCountRequestableCrafts() {
        return 0L;
    }

    @Override
    public void reset() {
        stack.reset();
    }

    @Override
    public boolean isSameType(IItemStack other) {
        return stack.isSameType(((ItemStack) other).stack);
    }

    @Override
    public IItemStack copy() {
        return new ItemStack(stack.copy());
    }

    @Override
    public void setStackSize(long size) {
        stack.setStackSize(size);
    }
}

package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;

import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

@Mixin(value = CraftingPlanSummaryEntry.class)
public class CraftingPlanSummaryEntryMixin implements ICraftingPlanSummaryEntry {

    @Override
    public IAEKey web$getWhat() {
        return (IAEKey) ((CraftingPlanSummaryEntry) (Object) this).getWhat();
    }

    @Override
    public long web$getMissingAmount() {
        return ((CraftingPlanSummaryEntry) (Object) this).getMissingAmount();
    }

    @Override
    public long web$getStoredAmount() {
        return ((CraftingPlanSummaryEntry) (Object) this).getStoredAmount();
    }

    @Override
    public long web$getCraftAmount() {
        return ((CraftingPlanSummaryEntry) (Object) this).getCraftAmount();
    }
}

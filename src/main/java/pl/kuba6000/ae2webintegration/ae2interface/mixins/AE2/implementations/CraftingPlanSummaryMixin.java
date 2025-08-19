package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import appeng.menu.me.crafting.CraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;

@Mixin(value = CraftingPlanSummary.class)
public class CraftingPlanSummaryMixin implements ICraftingPlanSummary {

    @Override
    public long web$getUsedBytes() {
        return ((CraftingPlanSummary) (Object) this).getUsedBytes();
    }

    @Override
    public boolean web$isSimulation() {
        return ((CraftingPlanSummary) (Object) this).isSimulation();
    }

    @Override
    public List<ICraftingPlanSummaryEntry> web$getEntries() {
        return (List<ICraftingPlanSummaryEntry>) (Object) ((CraftingPlanSummary) (Object) this).getEntries();
    }
}

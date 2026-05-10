package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

@Mixin(value = ICraftingJob.class, remap = false)
public interface AECraftingJobMixin extends IAECraftingJob {

    @Override
    public default boolean web$isSimulation() {
        return ((ICraftingJob) (Object) this).isSimulation();
    }

    @Override
    public default long web$getByteTotal() {
        return ((ICraftingJob) (Object) this).getByteTotal();
    }

    @Override
    public default void web$populatePlan(IItemList plan) {
        ((ICraftingJob) (Object) this).populatePlan((appeng.api.storage.data.IItemList<IAEStack<?>>) (Object) plan);
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public default ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
        appeng.api.storage.data.IItemList plan = new appeng.util.item.ItemList();
        ((ICraftingJob) (Object) this).populatePlan(plan);

        final List<ICraftingPlanSummaryEntry> entries = new ArrayList<>();
        for (Object obj : plan) {
            final IAEStack<?> captured = (IAEStack<?>) obj;
            entries.add(new ICraftingPlanSummaryEntry() {

                @Override
                public IAEKey web$getWhat() {
                    return (IAEKey) (Object) captured;
                }

                @Override
                public long web$getMissingAmount() {
                    return Math.max(0, captured.getCountRequestable() - captured.getStackSize());
                }

                @Override
                public long web$getStoredAmount() {
                    return captured.getStackSize();
                }

                @Override
                public long web$getCraftAmount() {
                    return captured.getCountRequestableCrafts();
                }
            });
        }

        final long bytes = ((ICraftingJob) (Object) this).getByteTotal();
        final boolean simulation = ((ICraftingJob) (Object) this).isSimulation();

        return new ICraftingPlanSummary() {

            @Override
            public long web$getUsedBytes() {
                return bytes;
            }

            @Override
            public boolean web$isSimulation() {
                return simulation;
            }

            @Override
            public List<ICraftingPlanSummaryEntry> web$getEntries() {
                return entries;
            }
        };
    }
}

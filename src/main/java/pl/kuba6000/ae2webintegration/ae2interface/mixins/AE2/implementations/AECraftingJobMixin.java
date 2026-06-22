package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;

@Mixin(value = ICraftingJob.class, remap = false)
public interface AECraftingJobMixin extends IAECraftingJob {

    @Override
    default boolean web$isSimulation() {
        return ((ICraftingJob) (Object) this).isSimulation();
    }

    @Override
    default long web$getByteTotal() {
        return ((ICraftingJob) (Object) this).getByteTotal();
    }

    @Override
    default ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
        IItemList<IAEItemStack> plan = AEApi.instance()
            .storage()
            .getStorageChannel(IItemStorageChannel.class)
            .createList();
        ((ICraftingJob) (Object) this).populatePlan(plan);

        final boolean simulation = ((ICraftingJob) (Object) this).isSimulation();
        final IAEMeInventoryItem inventory = grid.web$getStorageGrid()
            .web$getInventory();

        final List<ICraftingPlanSummaryEntry> entries = new ArrayList<>();
        for (IAEItemStack captured : plan) {
            final IAEKey key = (IAEKey) (Object) captured;

            final long stored;
            final long missing;
            if (simulation) {
                long needed = captured.getStackSize();
                long extracted = inventory.web$extractItems(key, needed, AEActionable.SIMULATE, grid);
                stored = extracted;
                missing = needed - extracted;
            } else {
                stored = captured.getStackSize();
                missing = 0L;
            }

            entries.add(new ICraftingPlanSummaryEntry() {

                @Override
                public IAEKey web$getWhat() {
                    return key;
                }

                @Override
                public long web$getMissingAmount() {
                    return missing;
                }

                @Override
                public long web$getStoredAmount() {
                    return stored;
                }

                @Override
                public long web$getCraftAmount() {
                    return captured.getCountRequestable();
                }

                @Override
                public long web$getCraftSteps() {
                    return 0L;
                }
            });
        }

        final long bytes = ((ICraftingJob) (Object) this).getByteTotal();

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

package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;

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
    default void web$populatePlan(IItemList plan) {
        ((ICraftingJob) (Object) this).populatePlan((appeng.api.storage.data.IItemList<IAEItemStack>) (Object) plan);
    }

    @Override
    default ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
        // 1.12.2 ICraftingJob does not have generateSummary directly.
        // Populate a plan and create a basic summary from it.
        appeng.api.storage.data.IItemList<IAEItemStack> plan = AEApi.instance()
            .storage()
            .getStorageChannel(IItemStorageChannel.class)
            .createList();
        ((ICraftingJob) (Object) this).populatePlan(plan);
        ICraftingJob job = (ICraftingJob) (Object) this;
        return new ICraftingPlanSummary() {

            @Override
            public long web$getUsedBytes() {
                return job.getByteTotal();
            }

            @Override
            public boolean web$isSimulation() {
                return job.isSimulation();
            }

            @Override
            public List<ICraftingPlanSummaryEntry> web$getEntries() {
                List<ICraftingPlanSummaryEntry> entries = new ArrayList<>();
                for (IAEItemStack stack : plan) {
                    IAEItemStack copy = stack.copy();
                    entries.add(new ICraftingPlanSummaryEntry() {

                        @Override
                        public IAEKey web$getWhat() {
                            return (IAEKey) (Object) copy;
                        }

                        @Override
                        public long web$getStoredAmount() {
                            return copy.getStackSize();
                        }

                        @Override
                        public long web$getMissingAmount() {
                            return copy.getCountRequestable() - copy.getStackSize();
                        }

                        @Override
                        public long web$getCraftAmount() {
                            return copy.getCountRequestable();
                        }
                    });
                }
                return entries;
            }
        };
    }
}

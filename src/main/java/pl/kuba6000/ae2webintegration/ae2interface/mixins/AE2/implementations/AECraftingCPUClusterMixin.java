package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.util.item.IAEStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class AECraftingCPUClusterMixin implements ICraftingCPUCluster {

    @Shadow
    private IItemList<IAEItemStack> waitingFor;

    @Unique
    private int web$internalID = -1;

    @Override
    public void web$setInternalID(int id) {
        web$internalID = id;
    }

    @Override
    public boolean web$hasCustomName() {
        return !((CraftingCPUCluster) (Object) this).getName()
            .isEmpty();
    }

    @Override
    public String web$getName() {
        return web$hasCustomName() ? ((CraftingCPUCluster) (Object) this).getName() : ("CPU #" + web$internalID);
    }

    @Override
    public long web$getAvailableStorage() {
        return ((CraftingCPUCluster) (Object) this).getAvailableStorage();
    }

    @Unique
    private boolean web$isUsedStorageAvailable = true;

    @Unique
    private boolean web$usedStorageInitialized = false;

    @Override
    public long web$getUsedStorage() {
        if (!web$usedStorageInitialized) {
            web$usedStorageInitialized = true;
            try {
                CraftingCPUCluster.class.getDeclaredMethod("getUsedStorage");
            } catch (NoSuchMethodException e) {
                web$isUsedStorageAvailable = false;
                return -1L;
            }
        }
        if (!web$isUsedStorageAvailable) return -1L;
        return ((CraftingCPUCluster) (Object) this).getUsedStorage();
    }

    @Override
    public long web$getCoProcessors() {
        return ((CraftingCPUCluster) (Object) this).getCoProcessors();
    }

    @Override
    public boolean web$isBusy() {
        return ((CraftingCPUCluster) (Object) this).isBusy();
    }

    @Override
    public void web$cancel() {
        ((CraftingCPUCluster) (Object) this).cancel();
    }

    @Override
    public IAEGenericStack web$getFinalOutput() {
        return (IAEGenericStack) ((CraftingCPUCluster) (Object) this).getFinalMultiOutput();
    }

    @Override
    public void web$getAllItems(IStackList list) {
        populateList(list, CraftingItemList.ACTIVE);
        populateList(list, CraftingItemList.PENDING);
        populateList(list, CraftingItemList.STORAGE);
    }

    @Override
    public IStackList web$getWaitingFor() {
        return (IStackList) (Object) waitingFor;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public long web$getActiveItems(IAEKey key) {
        IItemList<IAEStack<?>> items = new IAEStackList();
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(items, CraftingItemList.ACTIVE);
        IAEStack<?> found = items.findPrecise((IAEStack<?>) (Object) key);
        return found == null ? 0 : found.getStackSize();
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public long web$getPendingItems(IAEKey key) {
        IItemList<IAEStack<?>> items = new IAEStackList();
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(items, CraftingItemList.PENDING);
        IAEStack<?> found = items.findPrecise((IAEStack<?>) (Object) key);
        return found == null ? 0 : found.getStackSize();
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public long web$getStorageItems(IAEKey key) {
        IItemList<IAEStack<?>> items = new IAEStackList();
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(items, CraftingItemList.STORAGE);
        IAEStack<?> found = items.findPrecise((IAEStack<?>) (Object) key);
        return found == null ? 0 : found.getStackSize();
    }

    @SuppressWarnings("unchecked")
    private void populateList(IStackList list, CraftingItemList type) {
        ((CraftingCPUCluster) (Object) this).getModernListOfItem(
            (IItemList<IAEStack<?>>) (Object) list,
            type);
    }
}

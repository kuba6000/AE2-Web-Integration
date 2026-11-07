package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.ArrayList;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeNodeData;

@Mixin(value = CraftingTreeNode.class, remap = false)
public abstract class CraftingTreeNodeMixin implements ICraftingTreeNodeData {

    @Override
    @Accessor("what")
    public abstract @NotNull IAEItemStack web$getWhat();

    @Override
    @Accessor("nodes")
    public abstract @NotNull ArrayList<CraftingTreeProcess> web$getProcesses();

    @Override
    @Accessor("used")
    public abstract @NotNull IItemList<IAEItemStack> web$getUsed();

    @Override
    @Accessor("missing")
    public abstract long web$getMissing();

    @Override
    @Accessor("howManyEmitted")
    public abstract long web$getHowManyEmitted();
}

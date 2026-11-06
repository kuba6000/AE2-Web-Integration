package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingTreeProcessData;

@Mixin(value = CraftingTreeProcess.class, remap = false)
public abstract class CraftingTreeProcessMixin implements ICraftingTreeProcessData {

    @Override
    @Accessor("details")
    public abstract @NotNull ICraftingPatternDetails web$getDetails();

    @Override
    @Accessor("nodes")
    public abstract @NotNull Object2LongArrayMap<CraftingTreeNode> web$getNodes();

    @Override
    @Accessor("crafts")
    public abstract long web$getCrafts();

    @Override
    @Invoker("getAmountCrafted")
    public abstract @NotNull IAEItemStack web$getAmountCrafted(@NotNull IAEItemStack what);
}

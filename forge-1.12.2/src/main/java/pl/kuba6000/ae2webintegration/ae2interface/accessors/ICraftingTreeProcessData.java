package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingTreeNode;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;

/** Native process counters, read only during completed-job server-thread capture. */
public interface ICraftingTreeProcessData {

    @NotNull
    ICraftingPatternDetails web$getDetails();

    @NotNull
    Object2LongArrayMap<CraftingTreeNode> web$getNodes();

    long web$getCrafts();

    /** The pattern's output that the tree selected this process for, as one craft produces it. */
    @NotNull
    IAEItemStack web$getAmountCrafted(@NotNull IAEItemStack what);
}

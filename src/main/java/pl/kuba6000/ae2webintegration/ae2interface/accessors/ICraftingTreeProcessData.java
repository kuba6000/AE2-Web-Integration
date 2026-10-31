package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.crafting.CraftingTreeNode;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;

/** Native process counters, read only during completed-job server-thread capture. */
public interface ICraftingTreeProcessData {

    @NotNull
    ICraftingPatternDetails web$getDetails();

    @NotNull
    Object2LongArrayMap<CraftingTreeNode> web$getNodes();

    long web$getCrafts();
}

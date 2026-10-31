package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import java.util.ArrayList;

import org.jetbrains.annotations.NotNull;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftingTreeProcess;

/** Native node counters, read only during completed-job server-thread capture. */
public interface ICraftingTreeNodeData {

    @NotNull
    IAEItemStack web$getWhat();

    @NotNull
    ArrayList<CraftingTreeProcess> web$getProcesses();

    @NotNull
    IItemList<IAEItemStack> web$getUsed();

    long web$getMissing();

    long web$getHowManyEmitted();
}

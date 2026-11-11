package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import java.util.ArrayList;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import appeng.api.storage.data.IAEStack;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.resolvers.CraftableItemResolver;

/**
 * Native child requests of a normal-mode pattern task, read only during completed-plan server-thread capture.
 * The public getChildRequests copies the whole list and omits the recursion-start requests.
 */
public interface ICraftFromPatternTaskData {

    @NotNull
    ArrayList<CraftableItemResolver.RequestAndPerCraftAmount> web$getChildRequests();

    @NotNull
    Map<IAEStack<?>, CraftingRequest> web$getChildRecursionRequests();
}

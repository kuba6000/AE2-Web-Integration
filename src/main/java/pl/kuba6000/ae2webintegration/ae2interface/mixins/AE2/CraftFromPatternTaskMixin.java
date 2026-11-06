package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.ArrayList;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.storage.data.IAEStack;
import appeng.crafting.v2.CraftingRequest;
import appeng.crafting.v2.resolvers.CraftableItemResolver.CraftFromPatternTask;
import appeng.crafting.v2.resolvers.CraftableItemResolver.RequestAndPerCraftAmount;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftFromPatternTaskData;

@Mixin(value = CraftFromPatternTask.class, remap = false)
public abstract class CraftFromPatternTaskMixin implements ICraftFromPatternTaskData {

    @Override
    @Accessor("childRequests")
    public abstract @NotNull ArrayList<RequestAndPerCraftAmount> web$getChildRequests();

    @Override
    @Accessor("childRecursionRequests")
    public abstract @NotNull Map<IAEStack<?>, CraftingRequest> web$getChildRecursionRequests();
}

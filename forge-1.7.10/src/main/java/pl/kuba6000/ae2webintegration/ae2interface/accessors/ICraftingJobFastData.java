package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.NotNull;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;

/** Native retained tables, read only during completed-plan server-thread capture. */
public interface ICraftingJobFastData {

    @NotNull
    AbstractObject2LongMap<ICraftingPatternDetails> web$getTasks();

    @NotNull
    AbstractObject2LongMap<IAEStack<?>> web$getIngredients();

    @NotNull
    AbstractObject2LongMap<IAEStack<?>> web$getMissingIngredients();
}

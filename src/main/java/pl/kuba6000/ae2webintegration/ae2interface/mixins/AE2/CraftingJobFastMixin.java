package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.fast.CraftingJobFast;
import it.unimi.dsi.fastutil.objects.AbstractObject2LongMap;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.ICraftingJobFastData;

@Mixin(value = CraftingJobFast.class, remap = false)
public abstract class CraftingJobFastMixin implements ICraftingJobFastData {

    @Override
    @Accessor("tasks")
    public abstract @NotNull AbstractObject2LongMap<ICraftingPatternDetails> web$getTasks();

    @Override
    @Accessor("ingredients")
    public abstract @NotNull AbstractObject2LongMap<IAEStack<?>> web$getIngredients();

    @Override
    @Accessor("missingIngredients")
    public abstract @NotNull AbstractObject2LongMap<IAEStack<?>> web$getMissingIngredients();
}

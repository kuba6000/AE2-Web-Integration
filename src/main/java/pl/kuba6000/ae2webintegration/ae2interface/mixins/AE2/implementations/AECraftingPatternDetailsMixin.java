package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.crafting.IPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;

@Mixin(value = IPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IAECraftingPatternDetails {

    @Override
    public default IAEGenericStack[] web$getCondensedOutputs() {
        return ((IPatternDetails) this).getOutputs()
            .toArray(new IAEGenericStack[0]);
    }
}

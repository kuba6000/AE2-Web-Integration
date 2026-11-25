package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;

@Mixin(value = ICraftingPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IEncodedPatternDetails {

    @Override
    default IAEGenericStack[] web$getCondensedInputs() {
        return (IAEGenericStack[]) ((ICraftingPatternDetails) (Object) this).getCondensedInputs();
    }

    @Override
    default boolean web$isCraftingPattern() {
        return ((ICraftingPatternDetails) (Object) this).isCraftable();
    }

    @Override
    default IAEGenericStack[] web$getCondensedOutputs() {
        return (IAEGenericStack[]) ((ICraftingPatternDetails) (Object) this).getCondensedOutputs();
    }
}

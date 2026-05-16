package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;

@Mixin(value = ICraftingPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IAECraftingPatternDetails {

    @Override
    default IAEGenericStack[] web$getCondensedOutputs() {
        return (IAEGenericStack[]) ((ICraftingPatternDetails) (Object) this).getCondensedOutputs();
    }
}

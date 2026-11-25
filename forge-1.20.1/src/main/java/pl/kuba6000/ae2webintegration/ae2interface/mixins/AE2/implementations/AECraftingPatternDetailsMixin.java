package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.LinkedHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;

@Mixin(value = IPatternDetails.class, remap = false)
public interface AECraftingPatternDetailsMixin extends IEncodedPatternDetails {

    @Override
    default IAEGenericStack[] web$getCondensedInputs() {
        Map<AEKey, Long> quantities = new LinkedHashMap<>();
        for (IPatternDetails.IInput input : ((IPatternDetails) this).getInputs()) {
            if (input == null) continue;
            GenericStack primary = input.getPossibleInputs()[0];
            quantities.merge(primary.what(), primary.amount() * input.getMultiplier(), Long::sum);
        }
        IAEGenericStack[] inputs = new IAEGenericStack[quantities.size()];
        int index = 0;
        for (Map.Entry<AEKey, Long> entry : quantities.entrySet()) {
            inputs[index++] = (IAEGenericStack) (Object) new GenericStack(entry.getKey(), entry.getValue());
        }
        return inputs;
    }

    @Override
    default boolean web$isCraftingPattern() {
        return (Object) this instanceof IMolecularAssemblerSupportedPattern;
    }

    @Override
    default IAEGenericStack[] web$getCondensedOutputs() {
        return (IAEGenericStack[]) (Object) ((IPatternDetails) (Object) this).getOutputs();
    }
}

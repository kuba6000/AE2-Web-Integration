package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.crafting.ICraftingProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumKey;

@Mixin(value = ICraftingProvider.class, remap = false)
public interface CraftingMediumKeyMixin extends ICraftingMediumKey {
}

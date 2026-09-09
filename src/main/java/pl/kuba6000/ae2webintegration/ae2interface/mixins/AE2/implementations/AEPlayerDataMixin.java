package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;

@Mixin(targets = "appeng.api.features.PlayerRegistryInternal", remap = false)
public class AEPlayerDataMixin implements IAEPlayerData {

    @Shadow
    public int getPlayerId(UUID profileId) {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

    @Override
    public int web$getPlayerId(PlayerIdentity identity) {
        return getPlayerId(identity.uuid);
    }
}

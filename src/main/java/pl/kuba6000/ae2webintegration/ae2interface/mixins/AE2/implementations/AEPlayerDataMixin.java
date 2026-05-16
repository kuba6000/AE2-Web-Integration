package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.UUID;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.authlib.GameProfile;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;

@Mixin(targets = "appeng.api.features.PlayerRegistryInternal", remap = false)
public class AEPlayerDataMixin implements IAEPlayerData {

    @Shadow
    public int getPlayerId(UUID profileId) {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

    @Shadow
    public UUID getProfileId(int playerId) {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

    @Override
    public Object web$getPlayerProfile(int playerId) {
        UUID uuid = getProfileId(playerId);
        if (uuid == null) return null;
        GameProfile p = ServerLifecycleHooks.getCurrentServer()
            .getProfileCache()
            .get(uuid)
            .orElse(null);
        if (p == null) {
            p = new GameProfile(uuid, uuid.toString());
        }
        return new PlayerIdentity(p.getId(), p.getName());
    }

    @Override
    public int web$getPlayerId(UUID id) {
        return getPlayerId(id);
    }

    @Override
    public int web$getPlayerId(Object id) {
        return getPlayerId(((GameProfile) id).getId());
    }
}

package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.UUID;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.authlib.GameProfile;

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
    public GameProfile web$getPlayerProfile(int playerId) {
        UUID uuid = getProfileId(playerId);
        if (uuid == null) return null;
        // for (final EntityPlayer player : CommonHelper.proxy.getPlayers()) {
        // if (player.getUniqueID().equals(uuid)) {
        // return player.getGameProfile();
        // }
        // }
        GameProfile p = ServerLifecycleHooks.getCurrentServer()
            .getProfileCache()
            .get(uuid)
            .orElse(null);
        if (p == null) {
            p = new GameProfile(uuid, uuid.toString());
        }
        return p;
    }

    @Override
    public int web$getPlayerId(GameProfile id) {
        return getPlayerId(id.getId());
    }
}

package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.base.Optional;
import com.mojang.authlib.GameProfile;

import appeng.core.worlddata.IWorldPlayerMapping;
import cpw.mods.fml.common.FMLCommonHandler;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;

@Mixin(targets = "appeng.core.worlddata.PlayerData", remap = false)
public class AEPlayerDataMixin implements IAEPlayerData {

    @Shadow
    @Final
    private IWorldPlayerMapping playerMapping;

    @Shadow
    public int getPlayerID(@Nonnull final GameProfile profile) {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

    @Override
    public PlayerIdentity web$getPlayerProfile(int playerId) {
        Optional<UUID> maybe = playerMapping.get(playerId);
        if (!maybe.isPresent()) return null;
        UUID uuid = maybe.get();
        // for (final EntityPlayer player : CommonHelper.proxy.getPlayers()) {
        // if (player.getUniqueID().equals(uuid)) {
        // return player.getGameProfile();
        // }
        // }
        GameProfile p = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .func_152358_ax()
            .func_152652_a(uuid);
        if (p == null) {
            p = new GameProfile(uuid, uuid.toString());
        }
        return new PlayerIdentity(p.getId(), p.getName());
    }

    @Override
    public int web$getPlayerId(UUID id) {
        // getPlayerID requires a complete GameProfile (both UUID and name).
        // First try the server's profile cache (the player is online for
        // the /ae2webintegration auth flow, so their profile should be cached).
        try {
            GameProfile cached = FMLCommonHandler.instance()
                .getMinecraftServerInstance()
                .func_152358_ax()
                .func_152652_a(id);
            if (cached != null && cached.isComplete()) {
                return getPlayerID(cached);
            }
        } catch (Exception ignored) {}

        // Graceful fallback — the caller (e.g. WebData.setPassword) will save
        // the password hash but skip UUID↔ID map population until the player
        // logs in via the game (which triggers PlayerData registration).
        return -1;
    }

    @Override
    public int web$getPlayerId(Object profile) {
        return getPlayerID((GameProfile) profile);
    }
}

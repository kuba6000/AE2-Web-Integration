package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.Optional;
import java.util.UUID;

import net.minecraftforge.fml.common.FMLCommonHandler;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.authlib.GameProfile;

import appeng.core.worlddata.IWorldPlayerMapping;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;

@Mixin(targets = "appeng.core.worlddata.PlayerData", remap = false)
public class AEPlayerDataMixin implements IAEPlayerData {

    @Shadow
    @Final
    private IWorldPlayerMapping playerMapping;

    @Shadow
    public int getPlayerID(@NotNull final GameProfile profile) {
        throw new UnsupportedOperationException("Mixin failed to apply.");
    }

    @Override
    public PlayerIdentity web$getPlayerProfile(int playerId) {
        Optional<UUID> maybe = playerMapping.get(playerId);
        if (!maybe.isPresent()) return null;
        UUID uuid = maybe.get();
        GameProfile p = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerProfileCache()
            .getProfileByUUID(uuid);
        if (p == null) {
            p = new GameProfile(uuid, uuid.toString());
        }
        return new PlayerIdentity(p.getId(), p.getName());
    }

    @Override
    public int web$getPlayerId(PlayerIdentity identity) {
        return getPlayerID(new GameProfile(identity.uuid, identity.name));
    }
}

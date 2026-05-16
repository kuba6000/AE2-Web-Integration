package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nonnull;

import net.minecraftforge.fml.common.FMLCommonHandler;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.mojang.authlib.GameProfile;

import appeng.core.worlddata.IWorldPlayerMapping;
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
    public Object web$getPlayerProfile(int playerId) {
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
        return p;
    }

    @Override
    public int web$getPlayerId(Object profile) {
        if (profile instanceof GameProfile) {
            return getPlayerID((GameProfile) profile);
        }
        return -1;
    }

    @Override
    public int web$getPlayerId(UUID id) {
        GameProfile p = FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerProfileCache()
            .getProfileByUUID(id);
        if (p != null) {
            return getPlayerID(p);
        }
        return -1;
    }
}

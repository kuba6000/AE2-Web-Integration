package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.google.common.base.Optional;
import com.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import com.mojang.authlib.GameProfile;

import appeng.core.worlddata.IWorldPlayerMapping;
import cpw.mods.fml.common.FMLCommonHandler;

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
    public GameProfile web$getPlayerProfile(int playerId) {
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
        return p;
    }

    @Override
    public int web$getPlayerId(GameProfile id) {
        return getPlayerID(id);
    }
}

package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.EnumSet;
import java.util.HashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import com.mojang.authlib.GameProfile;

import appeng.api.config.SecurityPermissions;
import appeng.core.worlddata.WorldData;
import appeng.me.cache.SecurityCache;

@Mixin(value = SecurityCache.class, remap = false)
public class AESecurityGridMixin implements IAESecurityGrid {

    @Shadow
    @Final
    private HashMap<Integer, EnumSet<SecurityPermissions>> playerPerms;

    @Override
    public boolean web$isAvailable() {
        return ((SecurityCache) (Object) this).isAvailable();
    }

    @Override
    public long web$getSecurityKey() {
        return ((SecurityCache) (Object) this).getSecurityKey();
    }

    @Override
    public int web$getOwner() {
        return ((SecurityCache) (Object) this).getOwner();
    }

    @Override
    public GameProfile web$getOwnerProfile() {
        IAEPlayerData playerData = (IAEPlayerData) WorldData.instance()
            .playerData();
        return playerData.web$getPlayerProfile(web$getOwner());
    }

    @Override
    public boolean web$hasPermissions(int playerId) {
        if (web$getOwner() == playerId) return true;
        EnumSet<SecurityPermissions> permissions = playerPerms.get(playerId);
        if (permissions == null) {
            return false;
        }
        return permissions.containsAll(
            EnumSet.of(
                SecurityPermissions.BUILD,
                SecurityPermissions.EXTRACT,
                SecurityPermissions.INJECT,
                SecurityPermissions.CRAFT));
    }
}

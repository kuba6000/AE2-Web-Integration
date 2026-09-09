package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import java.util.EnumSet;
import java.util.HashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.config.SecurityPermissions;
import appeng.core.worlddata.WorldData;
import appeng.me.cache.SecurityCache;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IPlayerProfileLookup;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;

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

    @Unique
    private int web$getOwner() {
        return ((SecurityCache) (Object) this).getOwner();
    }

    @Override
    public PlayerIdentity web$getOwnerProfile() {
        IPlayerProfileLookup playerData = (IPlayerProfileLookup) WorldData.instance()
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

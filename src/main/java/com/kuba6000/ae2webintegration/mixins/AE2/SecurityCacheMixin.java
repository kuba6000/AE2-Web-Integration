package com.kuba6000.ae2webintegration.mixins.AE2;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.kuba6000.ae2webintegration.AE2Controller;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import appeng.api.config.SecurityPermissions;
import appeng.me.cache.SecurityCache;

@Mixin(value = SecurityCache.class, remap = false)
public class SecurityCacheMixin {

    @ModifyReturnValue(
        method = "hasPermission(Lnet/minecraft/entity/player/EntityPlayer;Lappeng/api/config/SecurityPermissions;)Z",
        at = @At("RETURN"))
    boolean ae2webintegration$forcePermissionOnAE2WebController(boolean ret, final EntityPlayer player,
        final SecurityPermissions permissions) {
        if (player.getGameProfile() == AE2Controller.AEControllerProfile) {
            return true;
        }
        return ret;
    }

}

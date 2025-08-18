package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import appeng.api.config.SecurityPermissions;
import appeng.me.cache.SecurityCache;
import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mixin(value = SecurityCache.class, remap = false)
public class SecurityCacheMixin {

    @ModifyReturnValue(
        method = "hasPermission(Lnet/minecraft/entity/player/EntityPlayer;Lappeng/api/config/SecurityPermissions;)Z",
        at = @At("RETURN"))
    boolean ae2webintegration$forcePermissionOnAE2WebController(boolean ret, final EntityPlayer player,
        final SecurityPermissions permissions) {
        if (player.getGameProfile() == IAEWebInterface.getInstance()
            .getAEWebGameProfile()) {
            return true;
        }
        return ret;
    }

}

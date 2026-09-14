package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.events.MENetworkSecurityChange;
import appeng.me.cache.SecurityCache;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSessions;

@Mixin(value = SecurityCache.class, remap = false)
public class SecurityCacheMixin {

    @Unique
    private boolean web$securityAvailable;

    @Inject(method = "updatePermissions(Lappeng/api/networking/events/MENetworkSecurityChange;)V", at = @At("RETURN"))
    private void web$permissionsRebuilt(MENetworkSecurityChange event, CallbackInfo callback) {
        if (Platform.isServer()) GridAccessSessions.permissionsChanged();
    }

    @Inject(method = "onUpdateTick()V", at = @At("RETURN"))
    private void web$availabilityChanged(CallbackInfo callback) {
        if (!Platform.isServer()) return;
        // Availability also changes with provider power/channels and GTNH's security startup delay.
        boolean available = ((SecurityCache) (Object) this).isAvailable();
        if (available != web$securityAvailable) {
            web$securityAvailable = available;
            GridAccessSessions.permissionsChanged();
        }
    }

    @ModifyReturnValue(
        method = "hasPermission(Lnet/minecraft/entity/player/EntityPlayer;Lappeng/api/config/SecurityPermissions;)Z",
        at = @At("RETURN"))
    boolean ae2webintegration$forcePermissionOnAE2WebController(boolean ret, final EntityPlayer player,
        final SecurityPermissions permissions) {
        if (player.getUniqueID()
            .equals(
                IAEWebInterface.getInstance()
                    .getAEWebUUID())) {
            return true;
        }
        return ret;
    }

}

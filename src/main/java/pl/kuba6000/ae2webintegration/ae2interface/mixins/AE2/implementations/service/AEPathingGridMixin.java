package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGrid;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.me.cache.PathGridCache;
import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

@Mixin(value = PathGridCache.class, remap = false)
public abstract class AEPathingGridMixin implements IAEPathingGrid {

    @Shadow
    @Final
    private IGrid myGrid;

    @Shadow
    private boolean recalculateControllerNextTick;

    @Shadow
    private boolean updateNetwork;

    @Inject(method = "recalcController()V", at = @At("RETURN"))
    private void web$controllerValidated(CallbackInfo callback) {
        if (Platform.isServer()) CoreEngine.GRID_IDENTITIES.controllerValidated((IAEGrid) myGrid);
    }

    @Override
    public boolean web$isNetworkBooting() {
        return recalculateControllerNextTick || updateNetwork || ((IPathingGrid) (Object) this).isNetworkBooting();
    }

    @Override
    public AEControllerState web$getControllerState() {
        return switch (((IPathingGrid) (Object) this).getControllerState()) {
            case CONTROLLER_CONFLICT -> AEControllerState.CONTROLLER_CONFLICT;
            case CONTROLLER_ONLINE -> AEControllerState.CONTROLLER_ONLINE;
            case NO_CONTROLLER -> AEControllerState.NO_CONTROLLER;
            default -> AEControllerState.UNSUPPORTED;
        };
    }
}

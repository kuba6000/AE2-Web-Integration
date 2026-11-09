package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.me.Grid;
import appeng.me.service.PathingService;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

@Mixin(value = PathingService.class, remap = false)
public class AEPathingGridMixin implements IAEPathingGrid {

    @Shadow
    @Final
    private Grid grid;

    @Shadow
    private boolean reboot;

    @Shadow
    private boolean recalculateControllerNextTick;

    @Inject(method = "updateControllerState", at = @At("RETURN"))
    private void web$controllerValidated(CallbackInfo ci) {
        CoreEngine.GRID_IDENTITIES.controllerValidated((IAEGrid) grid);
    }

    @Override
    public boolean web$isNetworkBooting() {
        // Native booting does not include topology changes waiting for the next pathing tick.
        return reboot || recalculateControllerNextTick || ((PathingService) (Object) this).isNetworkBooting();
    }

    @Override
    public AEControllerState web$getControllerState() {
        return switch (((PathingService) (Object) this).getControllerState()) {
            case CONTROLLER_CONFLICT -> AEControllerState.CONTROLLER_CONFLICT;
            case CONTROLLER_ONLINE -> AEControllerState.CONTROLLER_ONLINE;
            case NO_CONTROLLER -> AEControllerState.NO_CONTROLLER;
            default -> AEControllerState.UNSUPPORTED;
        };
    }
}

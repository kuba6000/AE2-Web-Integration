package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.pathing.IPathingGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

@Mixin(value = IPathingGrid.class, remap = false)
public interface AEPathingGridMixin extends IAEPathingGrid {

    @Override
    public default boolean web$isNetworkBooting() {
        return ((IPathingGrid) (Object) this).isNetworkBooting();
    }

    @Override
    public default AEControllerState web$getControllerState() {
        return switch (((IPathingGrid) (Object) this).getControllerState()) {
            case CONTROLLER_CONFLICT -> AEControllerState.CONTROLLER_CONFLICT;
            case CONTROLLER_ONLINE -> AEControllerState.CONTROLLER_ONLINE;
            case NO_CONTROLLER -> AEControllerState.NO_CONTROLLER;
            default -> AEControllerState.UNSUPPORTED;
        };
    }
}

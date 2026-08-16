package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.pathing.ControllerState;
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
        ControllerState state = ((IPathingGrid) (Object) this).getControllerState();
        if (state == ControllerState.CONTROLLER_CONFLICT) {
            return AEControllerState.CONTROLLER_CONFLICT;
        } else if (state == ControllerState.CONTROLLER_ONLINE) {
            return AEControllerState.CONTROLLER_ONLINE;
        } else if (state == ControllerState.NO_CONTROLLER) {
            return AEControllerState.NO_CONTROLLER;
        }
        return AEControllerState.UNSUPPORTED;
    }
}

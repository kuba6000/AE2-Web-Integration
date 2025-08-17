package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

import appeng.me.service.PathingService;

@Mixin(value = PathingService.class, remap = false)
public class AEPathingGridMixin implements IAEPathingGrid {

    @Override
    public boolean web$isNetworkBooting() {
        return ((PathingService) (Object) this).isNetworkBooting();
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

package com.kuba6000.ae2webintegration.ae2interface.implementations.service;

import com.kuba6000.ae2webintegration.ae2interface.implementations.IAEWeakObject;
import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

import appeng.api.networking.pathing.IPathingGrid;

public class AEPathingGrid extends IAEWeakObject<IPathingGrid> implements IAEPathingGrid {

    public AEPathingGrid(IPathingGrid object) {
        super(object);
    }

    @Override
    public boolean isNetworkBooting() {
        return get().isNetworkBooting();
    }

    @Override
    public AEControllerState getControllerState() {
        return switch (get().getControllerState()) {
            case CONTROLLER_CONFLICT -> AEControllerState.CONTROLLER_CONFLICT;
            case CONTROLLER_ONLINE -> AEControllerState.CONTROLLER_ONLINE;
            case NO_CONTROLLER -> AEControllerState.NO_CONTROLLER;
            default -> AEControllerState.UNSUPPORTED;
        };
    }
}

package com.kuba6000.ae2webintegration.core.interfaces.service;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;

public interface IAEPathingGrid {

    boolean web$isNetworkBooting();

    AEControllerState web$getControllerState();

}

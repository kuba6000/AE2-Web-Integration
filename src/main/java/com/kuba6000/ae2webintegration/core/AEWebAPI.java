package com.kuba6000.ae2webintegration.core;

import com.kuba6000.ae2webintegration.core.api.IAEWebInterface;
import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.mojang.authlib.GameProfile;

public class AEWebAPI implements IAEWebInterface {

    public static final AEWebAPI INSTANCE = new AEWebAPI();

    @Override
    public GameProfile getAEWebGameProfile() {
        return AE2Controller.AEControllerProfile;
    }

    @Override
    public void initAEInterface(IAE ae) {
        AE2Controller.AE2Interface = ae;
    }
}

package com.kuba6000.ae2webintegration.core;

import com.kuba6000.ae2webintegration.core.api.IAEWebInterface;
import com.mojang.authlib.GameProfile;

public class AEWebAPI implements IAEWebInterface {

    public static final AEWebAPI INSTANCE = new AEWebAPI();

    @Override
    public GameProfile getAEWebGameProfile() {
        return AE2Controller.AEControllerProfile;
    }
}

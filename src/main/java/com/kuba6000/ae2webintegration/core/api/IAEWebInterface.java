package com.kuba6000.ae2webintegration.core.api;

import com.kuba6000.ae2webintegration.core.AEWebAPI;
import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.mojang.authlib.GameProfile;

public interface IAEWebInterface {

    static IAEWebInterface getInstance() {
        return AEWebAPI.INSTANCE;
    }

    GameProfile getAEWebGameProfile();

    void initAEInterface(IAE ae);

}

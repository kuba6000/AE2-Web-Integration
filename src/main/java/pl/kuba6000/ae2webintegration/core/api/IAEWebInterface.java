package pl.kuba6000.ae2webintegration.core.api;

import com.mojang.authlib.GameProfile;

import pl.kuba6000.ae2webintegration.core.AEWebAPI;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

public interface IAEWebInterface {

    static IAEWebInterface getInstance() {
        return AEWebAPI.INSTANCE;
    }

    GameProfile getAEWebGameProfile();

    void initAEInterface(IAE ae);

}

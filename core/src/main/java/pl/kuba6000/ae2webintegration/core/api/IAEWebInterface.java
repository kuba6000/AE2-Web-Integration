package pl.kuba6000.ae2webintegration.core.api;

import java.util.UUID;

import pl.kuba6000.ae2webintegration.core.AEWebAPI;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

public interface IAEWebInterface {

    static IAEWebInterface getInstance() {
        return AEWebAPI.INSTANCE;
    }

    UUID getAEWebUUID();

    void initAEInterface(IAE ae);

}

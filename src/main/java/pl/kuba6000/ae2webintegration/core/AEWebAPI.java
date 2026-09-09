package pl.kuba6000.ae2webintegration.core;

import java.util.UUID;

import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

public class AEWebAPI implements IAEWebInterface {

    public static final AEWebAPI INSTANCE = new AEWebAPI();

    @Override
    public UUID getAEWebUUID() {
        return AE2Controller.AEControllerUUID;
    }

    @Override
    public void initAEInterface(IAE ae) {
        AE2Controller.AE2Interface = ae;
    }
}

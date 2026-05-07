package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class WebEngine {

    public static void init(IServerPlatform serverPlatform) {
        AE2Controller.serverPlatform = serverPlatform;
    }
}

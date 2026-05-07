package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class WebEngine {

    private static IServerPlatform platform;

    public static void init(IServerPlatform platformImpl) {
        platform = platformImpl;
    }

    public static IServerPlatform getPlatform() {
        return platform;
    }
}

package pl.kuba6000.ae2webintegration.ae2interface.proxy;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.WebEngine;

/**
 * Lifecycle coordinator for AE2 Web Integration.
 */
public class CommonProxy {

    public void preInit(Platform platform, String version) {
        WebEngine.init(platform, version, "-forge-1.20.1");
        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version {}", version);
    }

    public void onServerStarted() {
        WebEngine.onServerStarted();
    }

    public void onServerStopping() {
        WebEngine.onServerStopping();
    }
}

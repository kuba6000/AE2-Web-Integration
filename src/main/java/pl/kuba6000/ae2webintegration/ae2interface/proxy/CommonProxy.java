package pl.kuba6000.ae2webintegration.ae2interface.proxy;

import pl.kuba6000.ae2webintegration.ae2interface.AE2WebIntegration;
import pl.kuba6000.ae2webintegration.ae2interface.platform.Platform;
import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.GridData;
import pl.kuba6000.ae2webintegration.core.StartupHandler;
import pl.kuba6000.ae2webintegration.core.WebData;
import pl.kuba6000.ae2webintegration.core.WebEngine;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

/**
 * Lifecycle coordinator for AE2 Web Integration.
 */
public class CommonProxy {

    public void preInit(Platform platform, String version) {
        VersionChecker.setVersionIdentifier("-forge-1.20.1");
        WebEngine.init(platform, version);
        Config.init(platform.getConfigDirectory());
        WebData.loadData();
        GridData.loadData();
        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version {}", version);
    }

    public void onServerStarted() {
        AE2Controller.init();
        StartupHandler.logOutdatedWarning();
        StartupHandler.handleDiscordIntegration();
    }

    public void onServerStopping() {
        AE2Controller.stopHTTPServer();
    }
}

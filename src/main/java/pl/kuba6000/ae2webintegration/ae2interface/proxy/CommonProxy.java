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
 *
 * Called by the @Mod class (AE2WebIntegration) at appropriate lifecycle
 * points. In NeoForge 1.21.1 there is no ISidedProxy system; this class
 * simply groups initialization logic that the @Mod constructor and event
 * handlers delegate to.
 */
public class CommonProxy {

    /**
     * Called during mod construction (FMLCommonSetupEvent would also work, but
     * loading data earlier avoids race conditions with the web server).
     */
    public void preInit(Platform platform, String version) {
        VersionChecker.setVersionIdentifier("-neoforge-1.21.1");
        WebEngine.init(platform, version);
        Config.init(platform.getConfigDirectory());
        WebData.loadData();
        GridData.loadData();
        AE2WebIntegration.LOG.info("AE2WebIntegration loading at version {}", version);
    }

    /** Called when the integrated/dedicated server has fully started. */
    public void onServerStarted() {
        AE2Controller.init();
        StartupHandler.logOutdatedWarning();
        StartupHandler.handleDiscordIntegration();
    }

    /** Called when the server is about to stop. */
    public void onServerStopping() {
        AE2Controller.stopHTTPServer();
    }
}

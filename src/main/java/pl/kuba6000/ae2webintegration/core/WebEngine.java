package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

public class WebEngine {

    // Populated by the interface layer from the buildscript-generated mod version.
    private static volatile String modVersion;

    public static void init(IServerPlatform serverPlatform, String modVersion) {
        AE2Controller.serverPlatform = serverPlatform;
        Config.init(serverPlatform.getConfigDirectory());
        WebEngine.modVersion = modVersion;
    }

    public static void loadData() {
        WebData.loadData();
        GridData.loadData();
    }

    public static void onServerStarted() {
        AE2Controller.init();
        StartupHandler.logOutdatedWarning();
        StartupHandler.handleDiscordIntegration();
    }

    public static void onServerStopping() {
        AE2Controller.stopHTTPServer();
    }

    public static String getModVersion() {
        return modVersion;
    }
}

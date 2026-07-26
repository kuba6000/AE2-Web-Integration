package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class CoreEngine {

    // Populated by the interface layer from the buildscript-generated mod version.
    private static volatile String modVersion;

    public static void init(IServerPlatform serverPlatform, String modVersion, String versionIdentifier) {
        VersionChecker.setVersionIdentifier(versionIdentifier);
        AE2Controller.serverPlatform = serverPlatform;
        Config.init(serverPlatform.getConfigDirectory());
        CoreEngine.modVersion = modVersion;
        loadData();
    }

    private static void loadData() {
        CoreData.loadData();
        GridData.loadData();
    }

    public static void onServerStarted() {
        AE2Controller.init();
        StartupHandler.logOutdatedWarning();
        StartupHandler.handleDiscordIntegration();
    }

    public static void onServerStopping() {
        AE2Controller.stopHTTPServer();
        // Authorization must not survive into the next world loaded in this JVM.
        GridAccessSessions.clear();
    }

    public static String getModVersion() {
        return modVersion;
    }
}

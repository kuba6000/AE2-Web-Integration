package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

class UpdateNotifierTest {

    private static final PlayerIdentity PLAYER = new PlayerIdentity(
        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        "Player");

    @TempDir
    File configRoot;

    @BeforeEach
    void setUp() throws Exception {
        CoreEngine.init(new TestPlatform(configRoot), "1.0.0-forge-1.20.1", "-forge-1.20.1");
        ConfigBootstrap.checkForUpdatesValue = () -> true;

        setVersionCheckerField("latestTag", "2.0.0-forge-1.20.1");
        setVersionCheckerField("lastChecked", System.currentTimeMillis());
        VersionChecker.setVersionIdentifier("-forge-1.20.1");
    }

    @Test
    void onPlayerLoggedInDoesNotNotifyPlayersWithoutAdminNoticePermission() {
        RecordingMessenger messenger = new RecordingMessenger();

        UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, false);

        assertEquals(0, messenger.sentMessages);
    }

    @Test
    void onPlayerLoggedInNotifiesAdminWhenOutdated() {
        RecordingMessenger messenger = new RecordingMessenger();

        UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, true);

        assertEquals(1, messenger.sentMessages);
    }

    private static void setVersionCheckerField(String name, Object value) throws Exception {
        Field field = VersionChecker.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static class RecordingMessenger implements IPlayerMessenger {

        int sentMessages;

        @Override
        public void sendMessage(PlayerIdentity player, String message) {
            sentMessages++;
        }
    }

    private static class TestPlatform implements IServerPlatform {

        private final File configDirectory;

        TestPlatform(File configDirectory) {
            this.configDirectory = configDirectory;
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return configDirectory;
        }
    }
}

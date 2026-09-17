package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

class UpdateNotifierTest {

    private static final PlayerIdentity PLAYER = new PlayerIdentity(
        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        "Player");

    @Test
    void notifiesAuthorizedPlayersFromCachedReleaseAndHonorsOptOutAndStop() throws Exception {
        String releaseUrl = "https://github.com/kuba6000/AE2-Web-Integration/releases/tag/1.1.0-forge-1.7.10";
        String feed = "{\"version\":\"1.7.10\",\"releases\":{\"prerelease\":null,\"stable\":{"
            + "\"newest\":\"1.1.0\",\"timestamp\":1786902605,\"github_release_tag\":\"1.1.0-forge-1.7.10\","
            + "\"github_release_url\":\""
            + releaseUrl
            + "\",\"github_release_download_url\":\"https://github.com/kuba6000/AE2-Web-Integration/releases/download/1.1.0-forge-1.7.10/mod.jar\"}}}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = feed.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody()
                .write(bytes);
            exchange.close();
        });
        server.start();
        IConfigValue<Boolean> oldConfig = ConfigBootstrap.checkForUpdatesValue;
        // Wire a real local feed into the lifecycle owner without a production test-only setter.
        Field activeChecker = CoreEngine.class.getDeclaredField("versionChecker");
        activeChecker.setAccessible(true);
        Object previous = activeChecker.get(null);
        try (VersionChecker checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/"),
            "1.0.0-forge-1.7.10",
            "-forge-1.7.10")) {
            activeChecker.set(null, checker);
            ConfigBootstrap.checkForUpdatesValue = () -> true;
            RecordingMessenger messenger = new RecordingMessenger();
            UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, true);
            assertEquals(0, messenger.sentMessages);
            checker.checkForUpdates()
                .get(5, TimeUnit.SECONDS);
            UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, false);
            assertEquals(0, messenger.sentMessages);
            UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, true);
            assertEquals(1, messenger.sentMessages);
            assertTrue(messenger.lastMessage.contains("1.1.0-forge-1.7.10"));
            assertTrue(messenger.lastMessage.contains(releaseUrl));
            ConfigBootstrap.checkForUpdatesValue = () -> false;
            UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, true);
            assertEquals(1, messenger.sentMessages);
            CoreEngine.onServerStopping();
            assertNull(CoreEngine.getAvailableUpdate());
            assertTrue(
                checker.checkForUpdates()
                    .isCancelled());
        } finally {
            activeChecker.set(null, previous);
            ConfigBootstrap.checkForUpdatesValue = oldConfig;
            server.stop(0);
        }
    }

    @Test
    void onPlayerLoggedInDoesNotNotifyPlayersWithoutAdminNoticePermission() {
        RecordingMessenger messenger = new RecordingMessenger();

        UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, false);

        assertEquals(0, messenger.sentMessages);
    }

    private static class RecordingMessenger implements IPlayerMessenger {

        int sentMessages;
        String lastMessage;

        @Override
        public void sendMessage(PlayerIdentity player, String message) {
            sentMessages++;
            lastMessage = message;
        }
    }

}

package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

class UpdateNotifierTest {

    private static final PlayerIdentity PLAYER = new PlayerIdentity(
        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        "Player");

    @Test
    void onPlayerLoggedInDoesNotNotifyPlayersWithoutAdminNoticePermission() {
        RecordingMessenger messenger = new RecordingMessenger();

        UpdateNotifier.onPlayerLoggedIn(messenger, PLAYER, false);

        assertEquals(0, messenger.sentMessages);
    }

    private static class RecordingMessenger implements IPlayerMessenger {

        int sentMessages;

        @Override
        public void sendMessage(PlayerIdentity player, String message) {
            sentMessages++;
        }
    }

}

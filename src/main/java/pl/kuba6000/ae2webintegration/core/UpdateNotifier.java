package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class UpdateNotifier {

    private static final String UPDATE_MESSAGE = "----> AE2WebIntegration -> New version detected!"
        + " Consider updating at https://github.com/kuba6000/AE2-Web-Integration/releases/latest";

    public static void notifyPlayerIfOutdated(IPlayerMessenger messenger, PlayerIdentity player) {
        if (Config.CHECK_FOR_UPDATES() && VersionChecker.isOutdated()) {
            messenger.sendMessage(player, UPDATE_MESSAGE);
        }
    }

    public static void onPlayerLoggedIn(IPlayerMessenger messenger, PlayerIdentity player,
        boolean canReceiveAdminNotices) {
        if (!canReceiveAdminNotices) return;
        notifyPlayerIfOutdated(messenger, player);
    }
}

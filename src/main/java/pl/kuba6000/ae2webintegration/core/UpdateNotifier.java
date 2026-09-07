package pl.kuba6000.ae2webintegration.core;

import pl.kuba6000.ae2webintegration.core.api.IPlayerMessenger;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest;

public class UpdateNotifier {

    public static void notifyPlayerIfOutdated(IPlayerMessenger messenger, PlayerIdentity player) {
        if (!Config.CHECK_FOR_UPDATES()) return;
        ReleaseManifest.Release update = CoreEngine.getAvailableUpdate();
        if (update != null) {
            messenger.sendMessage(
                player,
                "AE2 Web Integration: new "
                    + (update.channel == ReleaseManifest.Channel.STABLE ? "stable release " : "prerelease ")
                    + update.tag
                    + " available at "
                    + update.releaseUrl);
        }
    }

    public static void onPlayerLoggedIn(IPlayerMessenger messenger, PlayerIdentity player,
        boolean canReceiveAdminNotices) {
        if (!canReceiveAdminNotices) return;
        notifyPlayerIfOutdated(messenger, player);
    }
}

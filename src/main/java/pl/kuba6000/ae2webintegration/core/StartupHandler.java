package pl.kuba6000.ae2webintegration.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.discord.DiscordManager;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class StartupHandler {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    public static void logOutdatedWarning() {
        if (Config.CHECK_FOR_UPDATES() && VersionChecker.isOutdated()) {
            LOG.warn(
                "You are not on latest version ! Consider updating to " + VersionChecker.getLatestTag()
                    + " at https://github.com/kuba6000/AE2-Web-Integration/releases/latest");
        }
    }

    public static void handleDiscordIntegration() {
        DiscordManager.init();
        if (!Config.AE_PUBLIC_MODE() && !Config.DISCORD_WEBHOOK()
            .isEmpty()) {
            DiscordManager.postMessageNonBlocking(
                new DiscordManager.DiscordEmbed("AE2 Web Integration", "Discord integration started!"));
        } else if (Config.AE_PUBLIC_MODE() && !Config.DISCORD_WEBHOOK()
            .isEmpty()) {
                DiscordManager.postMessageNonBlocking(
                    new DiscordManager.DiscordEmbed(
                        "AE2 Web Integration",
                        "Warning!\nDiscord integration webhook is set in the config,"
                            + " but the public mode is enabled!\nDiscord integration will be disabled!",
                        15548997));
            }
    }
}

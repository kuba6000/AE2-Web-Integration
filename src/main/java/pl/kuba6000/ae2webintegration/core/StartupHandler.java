package pl.kuba6000.ae2webintegration.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.discord.DiscordManager;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class StartupHandler {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /**
     * An empty configured password is a legitimate choice - it deliberately opens the web interface - so
     * it is not blocked. It is worth stating plainly what it grants, because "admin" here is not merely
     * an open page: {@code isAdmin()} skips the permission check on every endpoint, including ordering
     * and cancelling crafts, for every network on the server.
     */
    public static void logOpenAdminAccessWarning() {
        if (!Config.AE_PASSWORD()
            .isEmpty()) {
            return;
        }
        LOG.warn(
            "The admin password is empty, so anyone who can reach the web interface on port {} has admin access."
                + " That bypasses AE2 grid permissions on every network on this server, including ordering and"
                + " cancelling crafting jobs."
                + " Set 'password' in the config to require a login."
                + " (Access from localhost is controlled separately by 'allow_no_password_on_localhost'.)",
            Config.AE_PORT());
    }

    public static void logOutdatedWarning() {
        if (Config.CHECK_FOR_UPDATES() && VersionChecker.isOutdated()) {
            LOG.warn(
                "You are not on latest version ! Consider updating to {} at https://github.com/kuba6000/AE2-Web-Integration/releases/latest",
                VersionChecker.getLatestTag());
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
                DiscordManager.postMessageNonBlocking(new DiscordManager.DiscordEmbed("AE2 Web Integration", """
                    Warning!
                    Discord integration webhook is set in the config, but the public mode is enabled!
                    Discord integration will be disabled!""", DiscordManager.COLOR_RED));
            }
    }
}

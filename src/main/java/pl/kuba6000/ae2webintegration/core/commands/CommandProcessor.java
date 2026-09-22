package pl.kuba6000.ae2webintegration.core.commands;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.NotificationManager;

public class CommandProcessor {

    private CommandProcessor() {}

    /**
     * Reloads the configuration and restarts the HTTP server.
     *
     * @return a CommandResult with success/failure status and a human-readable message
     */
    public static CommandResult reload() {
        try {
            Config.reload();
            AE2Controller.stopHTTPServer();
            AE2Controller.startHTTPServer();
            NotificationManager.init();
            return CommandResult.success("Successfully reloaded the config and restarted the web server!");
        } catch (Exception e) {
            return CommandResult.error("Failed to reload config: " + e.getMessage());
        }
    }

    /**
     * Registers a player who initiated registration via the web interface.
     *
     * @param player the complete identity of the player
     * @param token  the confirmation token shown on the web interface
     * @return a CommandResult with success/failure status and a human-readable message
     */
    public static CommandResult registerPlayer(PlayerIdentity player, String token) {
        return switch (AuthService.confirmRegistration(player, token)) {
            case NOT_PENDING -> CommandResult
                .error("You have to initialize the registration on the web interface first!");
            case INVALID_TOKEN -> CommandResult.error("Invalid token!");
            case SUCCESS -> CommandResult.success("Registered successfully!");
        };
    }
}

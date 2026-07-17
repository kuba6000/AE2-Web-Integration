package pl.kuba6000.ae2webintegration.core;

import org.apache.commons.lang3.tuple.Pair;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public class CommandProcessor {

    private CommandProcessor() {}

    /**
     * Reloads the configuration and restarts the HTTP server.
     *
     * @param configReloader a Runnable that performs the Forge-specific config reload
     *                       (reads the Forge config file and applies values to ConfigBootstrap)
     * @return a CommandResult with success/failure status and a human-readable message
     */
    public static CommandResult reload(Runnable configReloader) {
        try {
            configReloader.run();
            AE2Controller.stopHTTPServer();
            AE2Controller.startHTTPServer();
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
        Pair<String, String> registration = AE2Controller.awaitingRegistration.get(player.uuid);
        if (registration == null) {
            return CommandResult.error("You have to initialize the registration on the web interface first!");
        }
        if (!registration.getLeft().equals(token)) {
            return CommandResult.error("Invalid token!");
        }
        if (!CoreData.setPassword(player, registration.getRight())) {
            return CommandResult.error("Failed to resolve AE2 player ID. Please try again while online.");
        }
        AE2Controller.awaitingRegistration.remove(player.uuid);
        return CommandResult.success("Registered successfully!");
    }
}

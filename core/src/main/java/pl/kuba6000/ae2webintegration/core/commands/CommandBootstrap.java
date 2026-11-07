package pl.kuba6000.ae2webintegration.core.commands;

import java.io.IOException;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;

/**
 * Defines ALL commands for AE2 Web Integration.
 * <p>
 * Called once during mod initialization with a platform-specific
 * {@link ICommandBuilder} implementation. The full command tree — literals,
 * arguments, permission levels, and handlers — is defined here using the
 * builder's fluent API.
 * <p>
 * Supported commands:
 * <ul>
 * <li>{@code /ae2webintegration reload} — reloads config and restarts the web server</li>
 * <li>{@code /ae2webintegration auth <token>} — completes web registration</li>
 * </ul>
 */
public class CommandBootstrap {

    public static final int ADMIN_PERMISSION_LEVEL = 4;

    private CommandBootstrap() {}

    /**
     * Builds and registers all commands through the given builder.
     */
    public static void init(ICommandBuilder builder) {
        ICommandBuilder root = builder.literal("ae2webintegration", 0);
        root.literal("reload", ADMIN_PERMISSION_LEVEL)
            .executes(CommandBootstrap::handleReload);
        root.literal("auth", 0)
            .argument("token")
            .executes(CommandBootstrap::handleAuth);
        root.literal("telemetry", 0)
            .argument("on|off|status")
            .executes(CommandBootstrap::handleTelemetry);
        builder.register();
    }

    private static void handleTelemetry(ICommandContext ctx) {
        if (!ctx.isSingleplayerOwner() && !ctx.hasPermission(ADMIN_PERMISSION_LEVEL)) {
            ctx.sendError("You do not have permission to use this command!");
            return;
        }
        String[] args = ctx.getArgs();
        if (args.length != 2 || !(args[1].equals("on") || args[1].equals("off") || args[1].equals("status"))) {
            ctx.sendError("/ae2webintegration telemetry <on|off|status>");
            return;
        }
        if (!args[1].equals("status")) {
            try {
                Config.setTelemetryEnabled(args[1].equals("on"));
            } catch (IOException | RuntimeException failure) {
                ctx.sendError("Could not save telemetry preference. The setting has not changed.");
                return;
            }
        }
        ctx.sendMessage(
            "Telemetry is " + (Config.INSTANCE.telemetry.enabled ? "enabled (disabled in development environments)."
                : "disabled."));
    }

    private static void handleReload(ICommandContext ctx) {
        if (!ctx.hasPermission(ADMIN_PERMISSION_LEVEL)) {
            ctx.sendError("You do not have permission to use this command!");
            return;
        }
        CommandResult result = CommandProcessor.reload();
        if (result.isSuccess()) {
            ctx.sendMessage(result.getMessage());
        } else {
            ctx.sendError(result.getMessage());
        }
    }

    private static void handleAuth(ICommandContext ctx) {
        String[] args = ctx.getArgs();
        if (args.length < 2) {
            ctx.sendError("/ae2webintegration auth <token>");
            return;
        }
        PlayerIdentity player = ctx.getPlayerIdentity();
        if (player == null) {
            ctx.sendError("This command can only be used by players!");
            return;
        }
        String token = args[1];
        CommandResult result = CommandProcessor.registerPlayer(player, token);
        if (result.isSuccess()) {
            ctx.sendMessage(result.getMessage());
        } else {
            ctx.sendError(result.getMessage());
        }
    }
}

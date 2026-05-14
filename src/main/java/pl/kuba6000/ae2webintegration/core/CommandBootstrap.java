package pl.kuba6000.ae2webintegration.core;

import java.util.UUID;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

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
 *   <li>{@code /ae2webintegration reload} — reloads config and restarts the web server</li>
 *   <li>{@code /ae2webintegration auth <token>} — completes web registration</li>
 * </ul>
 */
public class CommandBootstrap {

    private CommandBootstrap() {}

    /**
     * Builds and registers all commands through the given builder.
     */
    public static void init(ICommandBuilder builder) {
        builder
            .literal("ae2webintegration", 0)
                .literal("reload", 4)
                    .executes(CommandBootstrap::handleReload)
                .literal("auth", 0)
                    .argument("token")
                        .executes(CommandBootstrap::handleAuth);
        builder.register();
    }

    private static void handleReload(ICommandContext ctx) {
        if (!ctx.hasPermission(4)) {
            ctx.sendError("You do not have permission to use this command!");
            return;
        }
        CommandResult result = CommandProcessor.reload(ctx.getReloader());
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
        UUID playerId = ctx.getPlayerUUID();
        if (playerId == null) {
            ctx.sendError("This command can only be used by players!");
            return;
        }
        String token = args[1];
        CommandResult result = CommandProcessor.registerPlayer(playerId, token);
        if (result.isSuccess()) {
            ctx.sendMessage(result.getMessage());
        } else {
            ctx.sendError(result.getMessage());
        }
    }
}

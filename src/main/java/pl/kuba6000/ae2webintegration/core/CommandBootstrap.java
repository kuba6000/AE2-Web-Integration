package pl.kuba6000.ae2webintegration.core;

import java.util.UUID;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.ICommandRegistry;

/**
 * Defines ALL commands for AE2 Web Integration.
 * <p>
 * Called once during mod initialization with a platform-specific
 * {@link ICommandRegistry} implementation. All argument parsing and command
 * dispatch lives here — the interface layer handles only registration and
 * platform-specific sender wrappers.
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
     * Registers all commands through the given registry.
     */
    public static void init(ICommandRegistry registry) {
        registry.registerCommand("ae2webintegration", 0, ctx -> {
            String[] args = ctx.getArgs();
            if (args.length == 0) {
                ctx.sendError("/ae2webintegration <reload/auth>");
                return;
            }
            switch (args[0]) {
                case "reload":
                    handleReload(ctx);
                    break;
                case "auth":
                    handleAuth(ctx);
                    break;
                default:
                    ctx.sendError("/ae2webintegration <reload/auth>");
                    break;
            }
        });
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

package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.function.Consumer;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * Thin Forge 1.7.10 command wrapper. All argument parsing and dispatch logic
 * lives in {@code CommandBootstrap} (core) — this class only:
 * <ul>
 * <li>Provides the required {@link CommandBase} overrides</li>
 * <li>Skips command processing on the client side</li>
 * <li>Creates a {@link ForgeCommandContext} and delegates to the
 * handler registered via {@link ForgeCommandRegistry}</li>
 * </ul>
 */
public class BaseCommandHandler extends CommandBase {

    private final ForgeCommandRegistry registry;

    public BaseCommandHandler(ForgeCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommandName() {
        return "ae2webintegration";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "ae2webintegration <reload/auth>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        // Never process commands on the client side
        if (sender.getEntityWorld().isRemote) return;

        Consumer<ICommandContext> handler = registry.getHandler();
        if (handler != null) {
            handler.accept(new ForgeCommandContext(sender, args));
        }
    }
}

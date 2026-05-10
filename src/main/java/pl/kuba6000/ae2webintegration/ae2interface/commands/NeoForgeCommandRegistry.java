package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.function.Consumer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import com.mojang.brigadier.CommandDispatcher;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.ICommandRegistry;

/**
 * {@link ICommandRegistry} implementation wrapping NeoForge's Brigadier
 * {@link CommandDispatcher}.
 * <p>
 * Registers commands as literal nodes. Subcommand routing and argument
 * parsing is handled by {@code CommandBootstrap} in core — the registry
 * only sets up the root dispatch point with a basic permission check.
 */
public class NeoForgeCommandRegistry implements ICommandRegistry {

    private final CommandDispatcher<CommandSourceStack> dispatcher;

    public NeoForgeCommandRegistry(CommandDispatcher<CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void registerCommand(String name, int defaultPermission, Consumer<ICommandContext> handler) {
        dispatcher.register(
            Commands.literal(name)
                .requires(s -> s.hasPermission(defaultPermission))
                .executes(ctx -> {
                    handler.accept(new NeoForgeCommandContext(ctx));
                    return 1;
                }));
    }
}

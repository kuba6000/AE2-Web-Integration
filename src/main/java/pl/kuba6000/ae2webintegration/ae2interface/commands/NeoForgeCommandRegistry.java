package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.function.Consumer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.ICommandRegistry;

/**
 * {@link ICommandRegistry} implementation wrapping NeoForge's Brigadier
 * {@link CommandDispatcher}.
 * <p>
 * Registers the root command with proper Brigadier subcommands for "reload"
 * and "auth". Both subcommands delegate to the same {@code CommandBootstrap}
 * handler, which performs its own argument parsing. The {@code .requires()}
 * on the "reload" subcommand provides an early permission gate (level 4).
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
                // Root execute — handles args=[] (shows usage) or unknown args
                .executes(ctx -> {
                    handler.accept(new NeoForgeCommandContext(ctx));
                    return 1;
                })
                // /ae2webintegration reload
                .then(
                    Commands.literal("reload")
                        .requires(s -> s.hasPermission(4))
                        .executes(ctx -> {
                            handler.accept(new NeoForgeCommandContext(ctx));
                            return 1;
                        }))
                // /ae2webintegration auth <token>
                .then(
                    Commands.literal("auth")
                        .then(
                            Commands.argument("token", StringArgumentType.word())
                                .executes(ctx -> {
                                    handler.accept(new NeoForgeCommandContext(ctx));
                                    return 1;
                                }))));
    }
}

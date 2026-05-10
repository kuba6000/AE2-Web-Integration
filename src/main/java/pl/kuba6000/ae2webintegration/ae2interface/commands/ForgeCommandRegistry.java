package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.function.Consumer;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.ICommandRegistry;

/**
 * {@link ICommandRegistry} implementation for Forge 1.7.10.
 * <p>
 * Forge's command system requires a {@code CommandBase} subclass to be
 * registered via {@code FMLServerStartingEvent}. This registry stores the
 * handler from {@code CommandBootstrap.init()}; the thin
 * {@link BaseCommandHandler} retrieves it via {@link #getHandler()} when
 * the command is executed.
 */
public class ForgeCommandRegistry implements ICommandRegistry {

    private Consumer<ICommandContext> handler;

    @Override
    public void registerCommand(String name, int defaultPermission, Consumer<ICommandContext> handler) {
        this.handler = handler;
    }

    /** Returns the handler registered by {@code CommandBootstrap.init()}. */
    public Consumer<ICommandContext> getHandler() {
        return handler;
    }
}

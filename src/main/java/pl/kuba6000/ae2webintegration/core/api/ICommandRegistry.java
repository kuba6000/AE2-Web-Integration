package pl.kuba6000.ae2webintegration.core.api;

import java.util.function.Consumer;

/**
 * Platform-agnostic command registration interface.
 * <p>
 * Interface layer implementations wrap either Forge's
 * {@code FMLServerStartingEvent} (registering a {@code CommandBase}) or
 * NeoForge's {@code RegisterCommandsEvent} (building a Brigadier
 * {@code LiteralArgumentBuilder}).
 */
public interface ICommandRegistry {

    /**
     * Registers a command with the given name and default permission level.
     *
     * @param name               command name (e.g. "ae2webintegration")
     * @param defaultPermission  default permission level required (0 = everyone)
     * @param handler            receives an {@link ICommandContext} for each invocation
     */
    void registerCommand(String name, int defaultPermission, Consumer<ICommandContext> handler);
}

package pl.kuba6000.ae2webintegration.core.api;

import java.util.function.Consumer;

/**
 * Hierarchical command tree builder.
 * <p>
 * Core calls methods on this builder inside {@code CommandBootstrap.init()}
 * to define the full command tree (literals, arguments, handlers). The
 * interface layer translates the tree to platform-specific registration
 * (Brigadier for NeoForge, flat CommandBase for Forge 1.7.10).
 * <p>
 * Usage:
 * <pre>{@code
 * builder.literal("root", 0)
 *     .literal("sub", 2)
 *         .executes(ctx -> handle(ctx))
 *     .literal("other", 0)
 *         .argument("arg")
 *             .executes(ctx -> handleArg(ctx));
 * builder.register();
 * }</pre>
 */
public interface ICommandBuilder {

    /**
     * Descend into a literal child node with the given permission level.
     *
     * @param name       literal name (appears as-is in the command)
     * @param permission minimum permission level required (0 = everyone)
     * @return a builder for the child node
     */
    ICommandBuilder literal(String name, int permission);

    /**
     * Descend into an argument child node.
     *
     * @param name argument name (for help text)
     * @return a builder for the child node
     */
    ICommandBuilder argument(String name);

    /**
     * Sets the handler for the current node and returns the parent builder,
     * allowing sibling nodes to be defined.
     *
     * @param handler receives the {@link ICommandContext} when the command is executed
     * @return the parent builder (for chaining siblings)
     */
    ICommandBuilder executes(Consumer<ICommandContext> handler);

    /**
     * Finalizes registration with the platform-specific command system.
     * Default implementation is a no-op; platforms that need explicit
     * registration (e.g. Brigadier) override this.
     */
    default void register() {}
}

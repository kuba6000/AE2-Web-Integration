package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandBuilder} implementation for Forge 1.7.10.
 * <p>
 * Builds a tree of {@link CommandNode} instances as
 * {@code CommandBootstrap.init()} calls the fluent API. The tree is then
 * traversed by {@link BaseCommandHandler} at runtime to find the matching
 * handler for the given arguments.
 * <p>
 * {@link #register()} is a no-op because Forge commands are registered via
 * {@code FMLServerStartingEvent} + a {@code CommandBase} subclass, not via
 * the builder.
 */
public class CommandBuilder implements ICommandBuilder {

    /** A node in the command tree. */
    public static class CommandNode {

        public final String name;
        public final int permission;
        public final boolean isArgument;
        public final List<CommandNode> children = new ArrayList<>();
        public Consumer<ICommandContext> handler;

        CommandNode(String name, int permission, boolean isArgument) {
            this.name = name;
            this.permission = permission;
            this.isArgument = isArgument;
        }

        void addChild(CommandNode child) {
            children.add(child);
        }
    }

    private final CommandNode currentNode;
    private final ICommandBuilder parent;
    private final List<CommandNode> rootNodes;

    /** Root constructor. */
    public CommandBuilder() {
        this.currentNode = null;
        this.parent = null;
        this.rootNodes = new ArrayList<>();
    }

    private CommandBuilder(CommandNode currentNode, ICommandBuilder parent,
        List<CommandNode> rootNodes) {
        this.currentNode = currentNode;
        this.parent = parent;
        this.rootNodes = rootNodes;
    }

    @Override
    public ICommandBuilder literal(String name, int permission) {
        CommandNode child = new CommandNode(name, permission, false);
        if (parent == null) {
            rootNodes.add(child);
        } else if (currentNode != null) {
            currentNode.addChild(child);
        }
        return new CommandBuilder(child, this, rootNodes);
    }

    @Override
    public ICommandBuilder argument(String name) {
        CommandNode child = new CommandNode(name, 0, true);
        if (currentNode != null) {
            currentNode.addChild(child);
        }
        return new CommandBuilder(child, this, rootNodes);
    }

    @Override
    public ICommandBuilder executes(Consumer<ICommandContext> handler) {
        if (currentNode != null) {
            currentNode.handler = handler;
        }
        return parent;
    }

    /** Returns the top-level nodes built by the fluent calls. */
    public List<CommandNode> getRootNodes() {
        return rootNodes;
    }
}

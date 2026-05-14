package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandBuilder} implementation for Forge 1.7.10.
 * <p>
 * Builds a tree of {@link ForgeCommandNode} instances as
 * {@code CommandBootstrap.init()} calls the fluent API. The tree is then
 * traversed by {@link BaseCommandHandler} at runtime to find the matching
 * handler for the given arguments.
 * <p>
 * {@link #register()} is a no-op because Forge commands are registered via
 * {@code FMLServerStartingEvent} + a {@code CommandBase} subclass, not via
 * the builder.
 */
public class ForgeCommandBuilder implements ICommandBuilder {

    /** A node in the command tree. */
    public static class ForgeCommandNode {

        public final String name;
        public final int permission;
        public final boolean isArgument;
        public final List<ForgeCommandNode> children = new ArrayList<>();
        public Consumer<ICommandContext> handler;

        ForgeCommandNode(String name, int permission, boolean isArgument) {
            this.name = name;
            this.permission = permission;
            this.isArgument = isArgument;
        }

        void addChild(ForgeCommandNode child) {
            children.add(child);
        }
    }

    private final ForgeCommandNode currentNode;
    private final ICommandBuilder parent;
    private final List<ForgeCommandNode> rootNodes;

    /** Root constructor. */
    public ForgeCommandBuilder() {
        this.currentNode = null;
        this.parent = null;
        this.rootNodes = new ArrayList<>();
    }

    private ForgeCommandBuilder(ForgeCommandNode currentNode, ICommandBuilder parent,
        List<ForgeCommandNode> rootNodes) {
        this.currentNode = currentNode;
        this.parent = parent;
        this.rootNodes = rootNodes;
    }

    @Override
    public ICommandBuilder literal(String name, int permission) {
        ForgeCommandNode child = new ForgeCommandNode(name, permission, false);
        if (parent == null) {
            rootNodes.add(child);
        } else if (currentNode != null) {
            currentNode.addChild(child);
        }
        return new ForgeCommandBuilder(child, this, rootNodes);
    }

    @Override
    public ICommandBuilder argument(String name) {
        ForgeCommandNode child = new ForgeCommandNode(name, 0, true);
        if (currentNode != null) {
            currentNode.addChild(child);
        }
        return new ForgeCommandBuilder(child, this, rootNodes);
    }

    @Override
    public ICommandBuilder executes(Consumer<ICommandContext> handler) {
        if (currentNode != null) {
            currentNode.handler = handler;
        }
        return parent;
    }

    /** Returns the top-level nodes built by the fluent calls. */
    public List<ForgeCommandNode> getRootNodes() {
        return rootNodes;
    }
}

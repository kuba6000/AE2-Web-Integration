package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandBuilder} implementation that builds a Brigadier command tree
 * and registers it with the {@link CommandDispatcher}.
 * <p>
 * The tree is stored in simple data nodes during the fluent construction phase
 * (no Brigadier objects involved). At {@link #register()} time the data tree
 * is walked depth-first and the full Brigadier tree is built from scratch,
 * ensuring every subtree is complete before it's attached via {@code .then()}.
 */
public class NeoForgeCommandBuilder implements ICommandBuilder {

    /** A node in the command tree. */
    private static class CommandNode {

        final String name;
        final int permission;
        final boolean isArgument;
        final List<CommandNode> children = new ArrayList<>();
        Consumer<ICommandContext> handler;

        CommandNode(String name, int permission, boolean isArgument) {
            this.name = name;
            this.permission = permission;
            this.isArgument = isArgument;
        }
    }

    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final ICommandBuilder fluentParent;
    private final boolean isRoot;
    private final CommandNode currentNode;
    private final List<CommandNode> rootNodes;

    /** Root constructor — called by AE2WebIntegration. */
    public NeoForgeCommandBuilder(CommandDispatcher<CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
        this.fluentParent = null;
        this.isRoot = true;
        this.currentNode = null;
        this.rootNodes = new ArrayList<>();
    }

    /** Child constructor — created by {@link #literal} and {@link #argument}. */
    private NeoForgeCommandBuilder(ICommandBuilder fluentParent, CommandNode currentNode, List<CommandNode> rootNodes) {
        this.dispatcher = null;
        this.fluentParent = fluentParent;
        this.isRoot = false;
        this.currentNode = currentNode;
        this.rootNodes = rootNodes;
    }

    @Override
    public ICommandBuilder literal(String name, int permission) {
        CommandNode child = new CommandNode(name, permission, false);

        if (isRoot) {
            rootNodes.add(child);
        } else if (currentNode != null) {
            currentNode.children.add(child);
        }

        return new NeoForgeCommandBuilder(this, child, rootNodes);
    }

    @Override
    public ICommandBuilder argument(String name) {
        CommandNode child = new CommandNode(name, 0, true);

        if (currentNode != null) {
            currentNode.children.add(child);
        }

        return new NeoForgeCommandBuilder(this, child, rootNodes);
    }

    @Override
    public ICommandBuilder executes(Consumer<ICommandContext> handler) {
        if (currentNode != null) {
            currentNode.handler = handler;
        }
        return fluentParent;
    }

    @Override
    public void register() {
        for (CommandNode root : rootNodes) {
            dispatcher.register(buildLiteral(root));
        }
    }

    /** Builds a Brigadier {@link LiteralArgumentBuilder} from a data node. */
    private static LiteralArgumentBuilder<CommandSourceStack> buildLiteral(CommandNode node) {
        LiteralArgumentBuilder<CommandSourceStack> lit = Commands.literal(node.name)
            .requires(s -> s.hasPermission(node.permission));

        if (node.handler != null) {
            lit.executes(ctx -> {
                node.handler.accept(new NeoForgeCommandContext(ctx));
                return 1;
            });
        }

        for (CommandNode child : node.children) {
            lit.then(buildChild(child));
        }

        return lit;
    }

    /** Builds a child node (literal or argument) from a data node. */
    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> buildChild(CommandNode node) {
        if (node.isArgument) {
            RequiredArgumentBuilder<CommandSourceStack, String> arg = Commands
                .argument(node.name, StringArgumentType.word());

            if (node.handler != null) {
                arg.executes(ctx -> {
                    node.handler.accept(new NeoForgeCommandContext(ctx));
                    return 1;
                });
            }

            for (CommandNode child : node.children) {
                arg.then(buildChild(child));
            }

            return arg;
        } else {
            return buildLiteral(node);
        }
    }
}

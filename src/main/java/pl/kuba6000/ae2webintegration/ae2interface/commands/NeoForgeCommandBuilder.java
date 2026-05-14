package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandBuilder} implementation that builds a Brigadier command tree
 * and registers it with the {@link CommandDispatcher}.
 * <p>
 * The tree is built incrementally as {@code CommandBootstrap} calls
 * {@link #literal}, {@link #argument}, and {@link #executes}. Top-level
 * literals are collected and registered in {@link #register()}, which is
 * called by {@code CommandBootstrap.init()} after the full tree is defined.
 * <p>
 * The fluent parent (returned by {@link #executes}) is tracked separately
 * from root-level detection — this ensures siblings are added at the correct
 * tree depth.
 */
public class NeoForgeCommandBuilder implements ICommandBuilder {

    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final ICommandBuilder fluentParent;
    private final boolean isRoot;
    private final ArgumentBuilder<CommandSourceStack, ?> node;
    private final List<LiteralArgumentBuilder<CommandSourceStack>> rootLiterals;

    /** Root constructor — called by AE2WebIntegration. */
    public NeoForgeCommandBuilder(CommandDispatcher<CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
        this.fluentParent = null;
        this.isRoot = true;
        this.node = null;
        this.rootLiterals = new ArrayList<>();
    }

    /** Child constructor — created by {@link #literal} and {@link #argument}. */
    private NeoForgeCommandBuilder(ICommandBuilder fluentParent, ArgumentBuilder<CommandSourceStack, ?> node,
        List<LiteralArgumentBuilder<CommandSourceStack>> rootLiterals) {
        this.dispatcher = null;
        this.fluentParent = fluentParent;
        this.isRoot = false;
        this.node = node;
        this.rootLiterals = rootLiterals;
    }

    @Override
    public ICommandBuilder literal(String name, int permission) {
        LiteralArgumentBuilder<CommandSourceStack> child = Commands.literal(name)
            .requires(s -> s.hasPermission(permission));

        if (isRoot) {
            // Top-level literal on the root builder — collect for later registration
            rootLiterals.add(child);
        } else {
            // Child literal — attach to current node via .then()
            addChild(child);
        }

        return new NeoForgeCommandBuilder(this, child, rootLiterals);
    }

    @Override
    public ICommandBuilder argument(String name) {
        RequiredArgumentBuilder<CommandSourceStack, String> child = Commands.argument(name, StringArgumentType.word());
        addChild(child);
        return new NeoForgeCommandBuilder(this, child, rootLiterals);
    }

    @Override
    public ICommandBuilder executes(Consumer<ICommandContext> handler) {
        node.executes(ctx -> {
            handler.accept(new NeoForgeCommandContext(ctx));
            return 1;
        });
        return fluentParent;
    }

    @Override
    public void register() {
        for (LiteralArgumentBuilder<CommandSourceStack> literal : rootLiterals) {
            dispatcher.register(literal);
        }
    }

    private void addChild(ArgumentBuilder<CommandSourceStack, ?> child) {
        if (node instanceof LiteralArgumentBuilder) {
            ((LiteralArgumentBuilder<CommandSourceStack>) node).then(child);
        } else if (node instanceof RequiredArgumentBuilder) {
            ((RequiredArgumentBuilder<CommandSourceStack, ?>) node).then(child);
        }
    }
}

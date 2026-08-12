package pl.kuba6000.ae2webintegration.ae2interface.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/**
 * {@link ICommandContext} implementation wrapping a NeoForge Brigadier
 * {@link CommandContext<CommandSourceStack>}.
 * <p>
 * Args are extracted from the raw command input string by splitting on
 * whitespace and dropping the first element (the command name).
 */
public class CommandContext implements ICommandContext {

    private final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context;
    private final String[] args;

    public CommandContext(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        this.context = context;
        this.args = parseArgs(context.getInput());
    }

    private static String[] parseArgs(String input) {
        String[] parts = input.split(" ");
        if (parts.length <= 1) {
            return new String[0];
        }
        String[] result = new String[parts.length - 1];
        System.arraycopy(parts, 1, result, 0, parts.length - 1);
        return result;
    }

    @Override
    public String[] getArgs() {
        return args;
    }

    @Override
    public PlayerIdentity getPlayerIdentity() {
        ServerPlayer player = context.getSource()
            .getPlayer();
        return player != null ? new PlayerIdentity(
            player.getUUID(),
            player.getGameProfile()
                .getName())
            : null;
    }

    @Override
    public boolean hasPermission(int level) {
        return context.getSource()
            .hasPermission(level);
    }

    @Override
    public void sendMessage(String text) {
        context.getSource()
            .sendSuccess(
                () -> Component.literal(text)
                    .withStyle(ChatFormatting.GREEN),
                false);
    }

    @Override
    public void sendError(String text) {
        context.getSource()
            .sendFailure(
                Component.literal(text)
                    .withStyle(ChatFormatting.RED));
    }

    @Override
    public Runnable getReloader() {
        return Config::reloadFromDisk;
    }
}

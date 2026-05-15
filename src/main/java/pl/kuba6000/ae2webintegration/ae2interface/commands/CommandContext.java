package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandContext} implementation wrapping a Brigadier
 * {@link com.mojang.brigadier.context.CommandContext<CommandSourceStack>}.
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
    public UUID getPlayerUUID() {
        ServerPlayer player = context.getSource()
            .getPlayer();
        return player != null ? player.getUUID() : null;
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
        return () -> {};
    }
}

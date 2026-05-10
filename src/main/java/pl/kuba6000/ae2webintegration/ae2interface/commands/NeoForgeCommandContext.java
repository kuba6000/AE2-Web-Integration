package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.context.CommandContext;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;

/**
 * {@link ICommandContext} implementation wrapping a NeoForge Brigadier
 * {@link CommandContext<CommandSourceStack>}.
 * <p>
 * Args are extracted from the raw command input string by splitting on
 * whitespace and dropping the first element (the command name).
 */
public class NeoForgeCommandContext implements ICommandContext {

    private final CommandContext<CommandSourceStack> context;
    private final String[] args;

    public NeoForgeCommandContext(CommandContext<CommandSourceStack> context) {
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

    /**
     * Returns a Runnable that restarts the HTTP server.
     * <p>
     * NeoForge's {@code ConfigValue} instances are always live, so no explicit
     * config re-read is needed. The HTTP server restart ensures the new values
     * (already loaded by NeoForge) take effect (e.g. a different port).
     * {@link pl.kuba6000.ae2webintegration.core.CommandProcessor#reload}
     * handles the actual stop/start — this runnable is intentionally a no-op
     * to avoid double-restart.
     */
    @Override
    public Runnable getReloader() {
        return () -> {};
    }
}

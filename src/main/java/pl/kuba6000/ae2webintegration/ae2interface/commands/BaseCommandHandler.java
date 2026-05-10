package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import pl.kuba6000.ae2webintegration.core.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.api.CommandResult;

public class BaseCommandHandler {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("ae2webintegration")
                .then(
                    Commands.literal("reload")
                        .requires(p -> p.hasPermission(4))
                        .executes(BaseCommandHandler::reload))
                .then(
                    Commands.literal("auth")
                        .then(
                            RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("token", StringArgumentType.string())
                                .executes(BaseCommandHandler::auth))));
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        // The reloader will perform a Forge-specific config reload when ForgeConfig
        // is fully wired in a later phase. For now, CommandProcessor.reload() restarts
        // the HTTP server, which picks up the current Config static field values.
        CommandResult result = CommandProcessor.reload(() -> {});

        if (result.isSuccess()) {
            context.getSource()
                .sendSuccess(() -> Component.literal(ChatFormatting.GREEN + result.getMessage()), false);
        } else {
            context.getSource()
                .sendFailure(Component.literal(ChatFormatting.RED + result.getMessage()));
        }
        return result.isSuccess() ? 1 : -1;
    }

    private static int auth(CommandContext<CommandSourceStack> context) {
        final String token = StringArgumentType.getString(context, "token");

        ServerPlayer sender = context.getSource()
            .getPlayer();

        if (sender == null) {
            context.getSource()
                .sendFailure(Component.literal(ChatFormatting.RED + "This command can only be used by players!"));
            return -1;
        }

        UUID playerId = sender.getUUID();
        CommandResult result = CommandProcessor.registerPlayer(playerId, token);

        if (result.isSuccess()) {
            context.getSource()
                .sendSuccess(() -> Component.literal(ChatFormatting.GREEN + result.getMessage()), false);
        } else {
            context.getSource()
                .sendFailure(Component.literal(ChatFormatting.RED + result.getMessage()));
        }
        return result.isSuccess() ? 1 : -1;
    }
}

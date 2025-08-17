package com.kuba6000.ae2webintegration.core.commands;

import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import org.apache.commons.lang3.tuple.Pair;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.WebData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

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
                        .then(argument("token", StringArgumentType.string()))
                        .executes(BaseCommandHandler::auth)));
    }

    public static int reload(CommandContext<CommandSourceStack> context) {
        // Config.synchronizeConfiguration(); TODO
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        // context.getSource().sendSystemMessage(
        // Component.literal(
        // ChatFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"));
        context.getSource()
            .sendSuccess(
                () -> Component
                    .literal(ChatFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"),
                false);
        return 1;
    }

    public static int auth(CommandContext<CommandSourceStack> context) {
        final String token = StringArgumentType.getString(context, "token");

        ServerPlayer sender = context.getSource()
            .getPlayer();

        if (sender == null) {
            context.getSource()
                .sendFailure(Component.literal(ChatFormatting.RED + "This command can only be used by players!"));
            return -1;
        }

        UUID id = sender.getUUID();

        Pair<String, String> p = AE2Controller.awaitingRegistration.get(id);
        if (p == null) {
            context.getSource()
                .sendFailure(
                    Component.literal(
                        ChatFormatting.RED + "You have to initialize the registration on the web interface first!"));
            return -1;
        }

        if (!p.getLeft()
            .equals(token)) {
            context.getSource()
                .sendFailure(Component.literal(ChatFormatting.RED + "Invalid token!"));
            return -1;
        }

        WebData.setPassword(sender.getGameProfile(), p.getRight());

        AE2Controller.awaitingRegistration.remove(id);

        context.getSource()
            .sendSuccess(() -> Component.literal(ChatFormatting.GREEN.getName() + "Registered successfully!"), false);

        return 1;
    }
}

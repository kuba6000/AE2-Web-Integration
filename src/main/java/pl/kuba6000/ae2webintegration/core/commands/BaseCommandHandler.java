package pl.kuba6000.ae2webintegration.core.commands;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

import org.apache.commons.lang3.tuple.Pair;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.WebData;

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

    // force config reload, if the config watcher didn't work for some reason
    public static int reload(CommandContext<CommandSourceStack> context) {
        try {
            Method m = ConfigTracker.class.getDeclaredMethod("loadConfig", ModConfig.class, Path.class, Function.class);
            m.setAccessible(true);
            m.invoke(
                null,
                Config.CONFIG,
                Config.CONFIG.getFullPath(),
                (Function<ModConfig, ModConfigEvent>) (ModConfigEvent.Reloading::new));
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            context.getSource()
                .sendSuccess(
                    () -> Component
                        .literal(ChatFormatting.RED + "Error while reloading the config, restart the server instead!"),
                    false);
        }
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
            .sendSuccess(() -> Component.literal(ChatFormatting.GREEN + "Registered successfully!"), false);

        return 1;
    }
}

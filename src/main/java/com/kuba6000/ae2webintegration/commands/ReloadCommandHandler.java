package com.kuba6000.ae2webintegration.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import org.jetbrains.annotations.NotNull;

import com.kuba6000.ae2webintegration.AE2Controller;
import com.kuba6000.ae2webintegration.Config;

public class ReloadCommandHandler extends CommandBase {

    @Override
    public @NotNull String getName() {
        return "ae2webintegration";
    }

    @Override
    public @NotNull String getUsage(ICommandSender sender) {
        return "ae2webintegration <reload>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void execute(@NotNull MinecraftServer server, ICommandSender sender, String @NotNull [] args)
        throws CommandException {
        if (sender.getEntityWorld().isRemote) return;
        if (args.length == 0 || !args[0].equals("reload")) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "/ae2webintegration <reload>"));
            return;
        }
        Config.synchronizeConfiguration();
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        sender.sendMessage(
            new TextComponentString(
                TextFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"));
    }
}

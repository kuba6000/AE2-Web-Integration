package com.kuba6000.ae2webintegration.core.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.Config;

public class ReloadCommandHandler extends CommandBase {

    @Override
    public String getCommandName() {
        return "ae2webintegration";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "ae2webintegration <reload>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (sender.getEntityWorld().isRemote) return;
        if (args.length == 0 || !args[0].equals("reload")) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "/ae2webintegration <reload>"));
            return;
        }
        Config.synchronizeConfiguration();
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"));
    }
}

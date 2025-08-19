package pl.kuba6000.ae2webintegration.core.commands;

import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import org.apache.commons.lang3.tuple.Pair;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.WebData;

public class BaseCommandHandler extends CommandBase {

    @Override
    public String getCommandName() {
        return "ae2webintegration";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "ae2webintegration <reload/auth>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (sender.getEntityWorld().isRemote) return;
        if (args.length == 0 || (!args[0].equals("reload") && !args[0].equals("auth"))) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "/ae2webintegration <reload/auth>"));
            return;
        }
        if (args[0].equals("reload")) {
            if (!sender.canCommandSenderUseCommand(4, getCommandName())) {
                ChatComponentTranslation chatcomponenttranslation2 = new ChatComponentTranslation(
                    "commands.generic.permission",
                    new Object[0]);
                chatcomponenttranslation2.getChatStyle()
                    .setColor(EnumChatFormatting.RED);
                sender.addChatMessage(chatcomponenttranslation2);
                return;
            }
            Config.synchronizeConfiguration();
            AE2Controller.stopHTTPServer();
            AE2Controller.startHTTPServer();
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"));
        } else {
            // auth command
            if (args.length < 2) {
                sender
                    .addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "/ae2webintegration auth <token>"));
                return;
            }

            String token = args[1];

            if (!(sender instanceof EntityPlayerMP)) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "This command can only be used by players!"));
                return;
            }

            UUID id = ((EntityPlayerMP) sender).getUniqueID();

            Pair<String, String> p = AE2Controller.awaitingRegistration.get(id);
            if (p == null) {
                sender.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED
                            + "You have to initialize the registration on the web interface first!"));
                return;
            }

            if (!p.getLeft()
                .equals(token)) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Invalid token!"));
                return;
            }

            WebData.setPassword(((EntityPlayerMP) sender).getGameProfile(), p.getRight());

            AE2Controller.awaitingRegistration.remove(id);

            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Registered successfully!"));
        }
    }
}

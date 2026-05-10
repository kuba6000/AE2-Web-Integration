package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import pl.kuba6000.ae2webintegration.ae2interface.ForgeConfig;
import pl.kuba6000.ae2webintegration.core.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.api.CommandResult;

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
            CommandResult result = CommandProcessor
                .reload(() -> ForgeConfig.synchronizeConfiguration(Config.getProvider()));
            sender.addChatMessage(
                new ChatComponentText(
                    (result.isSuccess() ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + result.getMessage()));
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
            CommandResult result = CommandProcessor.registerPlayer(id, token);
            sender.addChatMessage(
                new ChatComponentText(
                    (result.isSuccess() ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + result.getMessage()));
        }
    }
}

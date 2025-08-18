package pl.kuba6000.ae2webintegration.core.commands;

import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.WebData;

public class BaseCommandHandler extends CommandBase {

    @Override
    public @NotNull String getName() {
        return "ae2webintegration";
    }

    @Override
    public @NotNull String getUsage(@NotNull ICommandSender sender) {
        return "ae2webintegration <reload/auth>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(@NotNull MinecraftServer server, ICommandSender sender, String @NotNull [] args)
        throws CommandException {
        if (sender.getEntityWorld().isRemote) return;
        if (args.length == 0 || (!args[0].equals("reload") && !args[0].equals("auth"))) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "/ae2webintegration <reload/auth>"));
            return;
        }
        if (args[0].equals("reload")) {
            if (!sender.canUseCommand(4, getName())) {
                TextComponentTranslation chatcomponenttranslation2 = new TextComponentTranslation(
                    "commands.generic.permission");
                chatcomponenttranslation2.getStyle()
                    .setColor(TextFormatting.RED);
                sender.sendMessage(chatcomponenttranslation2);
                return;
            }
            Config.synchronizeConfiguration();
            AE2Controller.stopHTTPServer();
            AE2Controller.startHTTPServer();
            sender.sendMessage(
                new TextComponentString(
                    TextFormatting.GREEN + "Successfully reloaded the config and restarted the web server!"));
        } else {
            // auth command
            if (args.length < 2) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "/ae2webintegration auth <token>"));
                return;
            }

            String token = args[1];

            if (!(sender instanceof EntityPlayerMP)) {
                sender.sendMessage(
                    new TextComponentString(TextFormatting.RED + "This command can only be used by players!"));
                return;
            }

            UUID id = ((EntityPlayerMP) sender).getUniqueID();

            Pair<String, String> p = AE2Controller.awaitingRegistration.get(id);
            if (p == null) {
                sender.sendMessage(
                    new TextComponentString(
                        TextFormatting.RED + "You have to initialize the registration on the web interface first!"));
                return;
            }

            if (!p.getLeft()
                .equals(token)) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "Invalid token!"));
                return;
            }

            WebData.setPassword(((EntityPlayerMP) sender).getGameProfile(), p.getRight());

            AE2Controller.awaitingRegistration.remove(id);

            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Registered successfully!"));
        }
    }
}

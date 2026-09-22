package pl.kuba6000.ae2webintegration.ae2interface.commands;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/**
 * {@link ICommandContext} implementation wrapping Forge 1.12.2's
 * {@link ICommandSender} and the raw command arguments.
 */
public class CommandContext implements ICommandContext {

    private final ICommandSender sender;
    private final String[] args;

    public CommandContext(ICommandSender sender, String[] args) {
        this.sender = sender;
        this.args = args;
    }

    @Override
    public String[] getArgs() {
        return args;
    }

    @Override
    public PlayerIdentity getPlayerIdentity() {
        if (sender instanceof EntityPlayerMP player) {
            return new PlayerIdentity(
                player.getUniqueID(),
                player.getGameProfile()
                    .getName());
        }
        return null;
    }

    @Override
    public boolean hasPermission(int level) {
        return sender.canUseCommand(level, "ae2webintegration");
    }

    @Override
    public void sendMessage(String text) {
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + text));
    }

    @Override
    public void sendError(String text) {
        sender.sendMessage(new TextComponentString(TextFormatting.RED + text));
    }

}

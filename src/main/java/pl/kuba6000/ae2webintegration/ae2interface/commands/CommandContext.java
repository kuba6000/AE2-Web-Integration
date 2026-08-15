package pl.kuba6000.ae2webintegration.ae2interface.commands;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import pl.kuba6000.ae2webintegration.ae2interface.config.Config;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/**
 * {@link ICommandContext} implementation wrapping Forge 1.7.10's
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
        if (sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            return new PlayerIdentity(
                player.getUniqueID(),
                player.getGameProfile()
                    .getName());
        }
        return null;
    }

    @Override
    public boolean hasPermission(int level) {
        return sender.canCommandSenderUseCommand(level, "ae2webintegration");
    }

    @Override
    public void sendMessage(String text) {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + text));
    }

    @Override
    public void sendError(String text) {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + text));
    }

    /**
     * Returns a Runnable that re-reads the Forge config file via
     * {@link Config#synchronizeConfiguration()} and restarts the
     * HTTP server. {@link pl.kuba6000.ae2webintegration.core.commands.CommandProcessor#reload}
     * handles the actual stop/start — this runnable only re-reads the config.
     */
    @Override
    public Runnable getReloader() {
        return () -> Config.synchronizeConfiguration();
    }
}

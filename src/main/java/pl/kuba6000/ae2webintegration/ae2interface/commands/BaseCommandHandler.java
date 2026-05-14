package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import pl.kuba6000.ae2webintegration.ae2interface.commands.ForgeCommandBuilder.ForgeCommandNode;

/**
 * Forge 1.7.10 command handler. Traverses the command tree built by
 * {@link ForgeCommandBuilder} to find the matching handler for the
 * player's arguments, then delegates to it via {@link ForgeCommandContext}.
 */
public class BaseCommandHandler extends CommandBase {

    private final List<ForgeCommandNode> rootNodes;

    public BaseCommandHandler(List<ForgeCommandNode> rootNodes) {
        this.rootNodes = rootNodes;
    }

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

        if (rootNodes.isEmpty()) return;
        ForgeCommandNode root = rootNodes.get(0); // "ae2webintegration"

        ForgeCommandNode matched = walkTree(root, args, 0);
        if (matched != null && matched.handler != null) {
            // Check permission on the matched node
            if (matched.permission > 0 && !sender.canCommandSenderUseCommand(matched.permission, getCommandName())) {
                sender.addChatMessage(
                    new ChatComponentText(EnumChatFormatting.RED + "You do not have permission to use this command!"));
                return;
            }
            matched.handler.accept(new ForgeCommandContext(sender, args));
        } else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "/ae2webintegration <reload/auth>"));
        }
    }

    /**
     * Recursively walks the command tree, matching arguments against literal
     * names and argument placeholders. Returns the deepest matching node that
     * has a handler, or {@code null} if no path matches.
     */
    private static ForgeCommandNode walkTree(ForgeCommandNode node, String[] args, int index) {
        if (index >= args.length) {
            return node.handler != null ? node : null;
        }

        String current = args[index];

        // Try to match a literal child by name
        for (ForgeCommandNode child : node.children) {
            if (!child.isArgument && child.name.equals(current)) {
                ForgeCommandNode result = walkTree(child, args, index + 1);
                if (result != null) return result;
            }
        }

        // Try to match an argument child (matches any token)
        for (ForgeCommandNode child : node.children) {
            if (child.isArgument) {
                ForgeCommandNode result = walkTree(child, args, index + 1);
                if (result != null) return result;
            }
        }

        return null;
    }
}

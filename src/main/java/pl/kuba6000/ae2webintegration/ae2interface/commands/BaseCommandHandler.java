package pl.kuba6000.ae2webintegration.ae2interface.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import pl.kuba6000.ae2webintegration.ae2interface.commands.CommandBuilder.CommandNode;

/**
 * Forge 1.12.2 command handler. Traverses the command tree built by
 * {@link CommandBuilder} to find the matching handler for the
 * player's arguments, then delegates to it via {@link CommandContext}.
 */
public class BaseCommandHandler extends CommandBase {

    private final List<CommandNode> rootNodes;

    public BaseCommandHandler(List<CommandNode> rootNodes) {
        this.rootNodes = rootNodes;
    }

    @Override
    public String getName() {
        return "ae2webintegration";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "ae2webintegration <reload/auth>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (sender.getEntityWorld().isRemote) return;

        if (rootNodes.isEmpty()) return;
        CommandNode root = rootNodes.get(0); // "ae2webintegration"

        CommandNode matched = walkTree(root, args, 0);
        if (matched != null && matched.handler != null) {
            // Check permission on the matched node
            if (matched.permission > 0 && !sender.canUseCommand(matched.permission, getName())) {
                sender.sendMessage(
                    new TextComponentString(TextFormatting.RED + "You do not have permission to use this command!"));
                return;
            }
            matched.handler.accept(new CommandContext(sender, args));
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "/ae2webintegration <reload/auth>"));
        }
    }

    /**
     * Recursively walks the command tree, matching arguments against literal
     * names and argument placeholders. Returns the deepest matching node that
     * has a handler, or {@code null} if no path matches.
     */
    private static CommandNode walkTree(CommandNode node, String[] args, int index) {
        if (index >= args.length) {
            return node.handler != null ? node : null;
        }

        String current = args[index];

        // Try to match a literal child by name
        for (CommandNode child : node.children) {
            if (!child.isArgument && child.name.equals(current)) {
                CommandNode result = walkTree(child, args, index + 1);
                if (result != null) return result;
            }
        }

        // Try to match an argument child (matches any token)
        for (CommandNode child : node.children) {
            if (child.isArgument) {
                CommandNode result = walkTree(child, args, index + 1);
                if (result != null) return result;
            }
        }

        return null;
    }
}

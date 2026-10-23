package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;

public class DiscordDestination implements INotificationDestination {

    public static final int COLOR_TURQUOISE = 0x1ABC9C;
    public static final int COLOR_RED = 0xED4245;
    public static final int COLOR_GREEN = 0x57F287;
    public static final int COLOR_YELLOW = 0xEDEA42;

    @Override
    public boolean isUsable() {
        return !Config.INSTANCE.discord.webhook.isEmpty();
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        ConfigSettings settings = Config.INSTANCE;
        String webhook = settings.discord.webhook;
        if (webhook.isEmpty()) return;

        DiscordPayload payload = null;
        if (message instanceof CraftingMessage craftingMessage) {
            payload = new DiscordPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.grid() + " ][ " + craftingMessage.cpuName() + " ]",
                "Crafting for `" + craftingMessage.outputItemName()
                    + " x"
                    + craftingMessage.outputItemAmount()
                    + "` "
                    + (craftingMessage.wasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.durationString(),
                craftingMessage.wasCancelled() ? COLOR_RED : COLOR_GREEN,
                settings.discord.roleId);
        } else if (message instanceof StatusMessage statusMessage) {
            StatusMessage.Severity severity = statusMessage.severity();
            int color = switch (severity) {
                case ERROR -> COLOR_RED;
                case WARNING -> COLOR_YELLOW;
                default -> COLOR_TURQUOISE;
            };
            payload = new DiscordPayload(
                statusMessage.title(),
                statusMessage.description(),
                color,
                settings.discord.roleId);
        }

        WebhookHelper.sendPayload(webhook, "", "", payload);
    }
}

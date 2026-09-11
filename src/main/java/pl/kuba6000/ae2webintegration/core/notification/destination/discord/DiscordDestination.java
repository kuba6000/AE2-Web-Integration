package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class DiscordDestination implements INotificationDestination {

    public static final int COLOR_TURQUOISE = 0x1ABC9C;
    public static final int COLOR_RED = 0xED4245;
    public static final int COLOR_GREEN = 0x57F287;
    public static final int COLOR_YELLOW = 0xEDEA42;

    @Override
    public boolean isUsable() {
        return !Config.DISCORD_WEBHOOK()
            .isEmpty();
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        String webhook = Config.DISCORD_WEBHOOK();
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
                craftingMessage.wasCancelled() ? COLOR_RED : COLOR_GREEN);
        } else if (message instanceof ErrorMessage errorMessage) {
            ErrorMessage.Severity severity = errorMessage.severity();
            int color = switch (severity) {
                case ERROR -> COLOR_RED;
                case WARNING -> COLOR_YELLOW;
                default -> 0;
            };
            if (color != 0) {
                payload = new DiscordPayload(errorMessage.title(), errorMessage.description(), color);
            } else {
                payload = new DiscordPayload(errorMessage.title(), errorMessage.description());
            }
        }

        WebhookHelper.sendPayload(webhook, "", "", payload);
    }
}

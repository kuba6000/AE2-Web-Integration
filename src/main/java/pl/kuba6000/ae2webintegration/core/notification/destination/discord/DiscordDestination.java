package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import java.io.IOException;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;

public class DiscordDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration - DISCORD INTEGRATION");
    private @Nullable DiscordCraftingCard card;

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
        String webhook = Config.INSTANCE.discord.webhook;
        if (webhook.isEmpty()) return;

        DiscordPayload payload = null;
        if (message instanceof CraftingMessage craftingMessage) {
            payload = new DiscordPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.gridName() + " ][ " + craftingMessage.cpuName() + " ]",
                "Requested by: " + (craftingMessage.requesters()
                    .isEmpty() ? "Unknown"
                        : craftingMessage.requesters()
                            .stream()
                            .map(player -> player.name)
                            .collect(Collectors.joining(", ")))
                    + "\nCrafting for `"
                    + craftingMessage.output()
                        .description()
                        .displayName()
                    + " x"
                    + craftingMessage.output()
                        .quantity()
                    + "` "
                    + (craftingMessage.wasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.durationString()
                    + "\nNetwork: "
                    + craftingMessage.gridName()
                    + "\nCPU: "
                    + craftingMessage.cpuName(),
                craftingMessage.wasCancelled() ? COLOR_RED : COLOR_GREEN);
            if (Config.INSTANCE.discord.imageCards) {
                byte[] png = null;
                try {
                    if (card == null) card = new DiscordCraftingCard();
                    png = card.render(craftingMessage, CoreEngine.getIconPack());
                } catch (IOException | RuntimeException | LinkageError failure) {
                    // Avoid including native names or webhook credentials in diagnostics.
                    LOG.warn(
                        "Crafting image unavailable; sending text notification ({})",
                        failure.getClass()
                            .getSimpleName());
                }
                if (png != null) {
                    payload.includeImage();
                    WebhookHelper.sendPng(webhook, payload, png);
                    return;
                }
            }
        } else if (message instanceof StatusMessage statusMessage) {
            StatusMessage.Severity severity = statusMessage.severity();
            int color = switch (severity) {
                case ERROR -> COLOR_RED;
                case WARNING -> COLOR_YELLOW;
                default -> 0;
            };
            if (color != 0) {
                payload = new DiscordPayload(statusMessage.title(), statusMessage.description(), color);
            } else {
                payload = new DiscordPayload(statusMessage.title(), statusMessage.description());
            }
        }

        WebhookHelper.sendPayload(webhook, "", "", payload);
    }
}

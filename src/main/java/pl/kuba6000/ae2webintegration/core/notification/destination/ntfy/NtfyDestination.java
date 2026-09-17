package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.util.ArrayList;
import java.util.List;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;

public class NtfyDestination implements INotificationDestination {

    @Override
    public boolean isUsable() {
        if (Config.NTFY_HOST()
            .isEmpty()) return false;
        if (Config.NTFY_TOPIC()
            .isEmpty()) return false;
        if (Config.NTFY_USER()
            .isEmpty()
            != Config.NTFY_PASSWORD()
                .isEmpty())
            return false;
        return true;
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        if (Config.NTFY_HOST()
            .isEmpty()) return;

        // If no protocol is set in config, assume https
        String host = Config.NTFY_HOST();
        if (!host.contains("http")) host = "https://" + host;

        NtfyPayload payload = null;
        if (message instanceof CraftingMessage craftingMessage) {
            List<String> tags = new ArrayList<>();
            if (craftingMessage.wasCancelled()) tags.add("x");
            else tags.add("heavy_check_mark");

            payload = new NtfyPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.grid() + " ][ " + craftingMessage.cpuName() + " ]",
                "Crafting for `" + craftingMessage.outputItemName()
                    + " x"
                    + craftingMessage.outputItemAmount()
                    + "` "
                    + (craftingMessage.wasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.durationString(),
                NtfyPayload.Priority.NORMAL,
                tags);
        } else if (message instanceof StatusMessage statusMessage) {
            StatusMessage.Severity severity = statusMessage.severity();
            List<String> tags = new ArrayList<>();
            NtfyPayload.Priority priority = NtfyPayload.Priority.NORMAL;
            switch (severity) {
                case ERROR:
                    priority = NtfyPayload.Priority.MAX;
                    tags.add("rotating_light");
                    break;
                case WARNING:
                    priority = NtfyPayload.Priority.HIGH;
                    tags.add("warning");
                    break;
            }
            payload = new NtfyPayload(statusMessage.title(), statusMessage.description(), priority, tags);
        }

        WebhookHelper.sendPayload(host, Config.NTFY_USER(), Config.NTFY_PASSWORD(), payload);
    }
}

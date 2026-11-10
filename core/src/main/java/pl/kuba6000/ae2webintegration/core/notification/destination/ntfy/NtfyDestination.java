package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.util.ArrayList;
import java.util.List;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyPayload.Priority;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage.Severity;

public class NtfyDestination implements INotificationDestination {

    @Override
    public boolean isUsable() {
        ConfigSettings settings = Config.INSTANCE;
        if (settings.ntfy.host.isEmpty()) return false;
        if (settings.ntfy.topic.isEmpty()) return false;
        if (settings.ntfy.user.isEmpty() != settings.ntfy.password.isEmpty()) return false;
        return true;
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        ConfigSettings settings = Config.INSTANCE;
        if (settings.ntfy.host.isEmpty()) return;

        // If no protocol is set in config, assume https
        String host = settings.ntfy.host;
        if (!host.regionMatches(true, 0, "https://", 0, "https://".length())
            && !host.regionMatches(true, 0, "http://", 0, "http://".length())) host = "https://" + host;

        NtfyPayload payload = null;
        if (message instanceof CraftingMessage craftingMessage) {
            List<String> tags = new ArrayList<>();
            if (craftingMessage.wasCancelled()) tags.add("x");
            else tags.add("heavy_check_mark");

            payload = new NtfyPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.gridName() + " ][ " + craftingMessage.cpuName() + " ]",
                "Requested by: " + craftingMessage.requesterNames()
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
                    + craftingMessage.durationString(),
                Priority.NORMAL,
                tags,
                settings);
        } else if (message instanceof StatusMessage statusMessage) {
            Severity severity = statusMessage.severity();
            List<String> tags = new ArrayList<>();
            Priority priority = Priority.NORMAL;
            switch (severity) {
                case ERROR:
                    priority = Priority.MAX;
                    tags.add("rotating_light");
                    break;
                case WARNING:
                    priority = Priority.HIGH;
                    tags.add("warning");
                    break;
            }
            payload = new NtfyPayload(statusMessage.title(), statusMessage.description(), priority, tags, settings);
        }

        WebhookHelper.sendPayload(host, settings.ntfy.user, settings.ntfy.password, payload);
    }
}

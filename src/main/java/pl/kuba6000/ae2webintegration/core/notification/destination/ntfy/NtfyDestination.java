package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.WebhookHelper;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

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
        } else if (message instanceof ErrorMessage errorMessage) {
            ErrorMessage.Severity severity = errorMessage.severity();
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
            };
            payload = new NtfyPayload(errorMessage.title(), errorMessage.description(), priority, tags);
        }

        WebhookHelper.sendPayload(Config.NTFY_HOST(), Config.NTFY_USER(), Config.NTFY_PASSWORD(), payload);
    }
}

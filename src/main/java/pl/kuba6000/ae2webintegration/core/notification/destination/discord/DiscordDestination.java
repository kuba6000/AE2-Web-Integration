package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class DiscordDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - DISCORD INTEGRATION");

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
        DiscordPayload payload = null;
        if (message instanceof CraftingMessage) {
            CraftingMessage craftingMessage = (CraftingMessage) message;
            payload = new DiscordPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.getGrid() + " ][ " + craftingMessage.getCpuName() + " ]",
                "Crafting for `" + craftingMessage.getOutputItemName()
                    + " x"
                    + craftingMessage.getOutputItemAmount()
                    + "` "
                    + (craftingMessage.isWasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.getDurationString(),
                craftingMessage.isWasCancelled() ? 15548997 : 5763719);
        } else if (message instanceof ErrorMessage) {
            ErrorMessage errorMessage = (ErrorMessage) message;
            ErrorMessage.Severity severity = errorMessage.getSeverity();
            int color = 0;
            switch (severity) {
                case ERROR:
                    color = 15548997;
                    break;
                case WARNING:
                    color = 15592002;
                    break;
            };
            if (color != 0) {
                payload = new DiscordPayload(errorMessage.getTitle(), errorMessage.getDescription(), color);
            } else {
                payload = new DiscordPayload(errorMessage.getTitle(), errorMessage.getDescription());
            }
        }

        if (payload == null) return;
        JsonObject json = payload.serializePayload();

        URL url = null;
        try {
            url = new URL(Config.DISCORD_WEBHOOK());

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(
                    json.toString()
                        .getBytes(StandardCharsets.UTF_8));
            }

            int code;
            if ((code = connection.getResponseCode()) != 200 && code != 204) {
                LOG.error("Error, response code: {}", code);
            }
        } catch (IOException e) {
            // throw new RuntimeException(e);
        }
    }
}

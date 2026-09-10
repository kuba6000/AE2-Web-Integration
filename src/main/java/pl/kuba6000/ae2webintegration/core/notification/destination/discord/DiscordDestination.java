package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
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

    public static final int COLOR_TURQUOISE = 0x1ABC9C;
    public static final int COLOR_RED = 0xED4245;
    public static final int COLOR_GREEN = 0x57F287;
    public static final int COLOR_YELLOW = 0xEDEA42;

    private static final int WEBHOOK_TIMEOUT_MILLIS = 10_000;

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
                craftingMessage.isWasCancelled() ? COLOR_RED : COLOR_GREEN);
        } else if (message instanceof ErrorMessage) {
            ErrorMessage errorMessage = (ErrorMessage) message;
            ErrorMessage.Severity severity = errorMessage.getSeverity();
            int color = 0;
            switch (severity) {
                case ERROR:
                    color = COLOR_RED;
                    break;
                case WARNING:
                    color = COLOR_YELLOW;
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

        HttpsURLConnection connection = null;
        try {
            URL url = new URL(webhook);

            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                LOG.error("Discord webhook URL must use HTTPS");
                return;
            }

            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(WEBHOOK_TIMEOUT_MILLIS);
            connection.setReadTimeout(WEBHOOK_TIMEOUT_MILLIS);
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
            if ((code = connection.getResponseCode()) != HttpURLConnection.HTTP_OK
                && code != HttpURLConnection.HTTP_NO_CONTENT) {
                LOG.error("Error, response code: {}", code);
            }
        } catch (IOException | IllegalArgumentException e) {
            // Exception messages may contain the webhook URL, including its secret token.
            LOG.error(
                "Discord webhook request failed ({})",
                e.getClass()
                    .getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class NtfyDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - NTFY INTEGRATION");

    private static final int WEBHOOK_TIMEOUT_MILLIS = 10_000;

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
                3,
                tags);
        } else if (message instanceof ErrorMessage errorMessage) {
            ErrorMessage.Severity severity = errorMessage.severity();
            List<String> tags = new ArrayList<>();
            int priority = 3;
            switch (severity) {
                case ERROR:
                    priority = 5;
                    tags.add("rotating_light");
                    break;
                case WARNING:
                    priority = 4;
                    tags.add("warning");
                    break;
            };
            payload = new NtfyPayload(errorMessage.title(), errorMessage.description(), priority, tags);
        }

        if (payload == null) return;
        JsonObject json = payload.serializePayload();

        HttpsURLConnection connection = null;
        try {
            URL url = new URL(Config.NTFY_HOST());
            String username = Config.NTFY_USER();
            String password = Config.NTFY_PASSWORD();

            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(WEBHOOK_TIMEOUT_MILLIS);
            connection.setReadTimeout(WEBHOOK_TIMEOUT_MILLIS);
            if (!username.isEmpty() && !password.isEmpty()) {
                String auth = username + ":" + password;
                byte[] encodedAuth = Base64.getEncoder()
                    .encode(auth.getBytes(StandardCharsets.UTF_8));
                String authHeaderValue = "Basic " + new String(encodedAuth);
                connection.setRequestProperty("Authorization", authHeaderValue);
            }
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");

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
                "Ntfy request failed ({})",
                e.getClass()
                    .getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

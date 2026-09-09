package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.io.IOException;
import java.io.OutputStream;
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

    @Override
    public boolean isUsable() {
        return !Config.NTFY_HOST()
            .isEmpty();
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
        if (message instanceof CraftingMessage) {
            CraftingMessage craftingMessage = (CraftingMessage) message;
            List<String> tags = new ArrayList<>();
            if (craftingMessage.isWasCancelled()) tags.add("x");
            else tags.add("heavy_check_mark");

            payload = new NtfyPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.getGrid() + " ][ " + craftingMessage.getCpuName() + " ]",
                "Crafting for `" + craftingMessage.getOutputItemName()
                    + " x"
                    + craftingMessage.getOutputItemAmount()
                    + "` "
                    + (craftingMessage.isWasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.getDurationString(),
                3,
                tags);
        } else if (message instanceof ErrorMessage) {
            ErrorMessage errorMessage = (ErrorMessage) message;
            ErrorMessage.Severity severity = errorMessage.getSeverity();
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
            payload = new NtfyPayload(errorMessage.getTitle(), errorMessage.getDescription(), priority, tags);
        }

        if (payload == null) return;
        JsonObject json = payload.serializePayload();

        URL url = null;
        try {
            url = new URL(Config.NTFY_HOST());
            String username = Config.NTFY_USER();
            String password = Config.NTFY_PASSWORD();

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder()
                .encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeaderValue = "Basic " + new String(encodedAuth);
            connection.setRequestProperty("Authorization", authHeaderValue);
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");

            System.out.println(json.toString());
            System.out.println(url);
            System.out.println(authHeaderValue);

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

package pl.kuba6000.ae2webintegration.core.notification.destination;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

public class WebhookHelper {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - WEBHOOK INTEGRATION");

    private static final int WEBHOOK_TIMEOUT_MILLIS = 10_000;

    public static void sendPayload(String webhookUrl, String username, String password, INotificationPayload payload) {
        if (webhookUrl == null) return;
        if (payload == null) return;
        JsonObject json = payload.serializePayload();

        HttpURLConnection connection = null;
        try {
            URL url = new URL(webhookUrl);

            connection = (HttpURLConnection) url.openConnection();
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
                "webhook request failed ({})",
                e.getClass()
                    .getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

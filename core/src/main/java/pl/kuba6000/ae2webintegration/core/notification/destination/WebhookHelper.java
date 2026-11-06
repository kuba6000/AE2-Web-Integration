package pl.kuba6000.ae2webintegration.core.notification.destination;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;

public class WebhookHelper {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - WEBHOOK INTEGRATION");

    private static final int WEBHOOK_TIMEOUT_MILLIS = 10_000;

    public static void sendPayload(String webhookUrl, String username, String password, INotificationPayload payload) {
        send(webhookUrl, username, password, payload, null);
    }

    /** Discord PNG attachment. Other notification payloads retain ordinary JSON delivery. */
    public static void sendPng(@NotNull String webhookUrl, @NotNull INotificationPayload payload,
        byte @NotNull [] png) {
        send(webhookUrl, "", "", payload, png);
    }

    private static void send(@Nullable String webhookUrl, @NotNull String username, @NotNull String password,
        @Nullable INotificationPayload payload, byte @Nullable [] png) {
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
            String boundary = "AE2Web" + UUID.randomUUID();
            connection.addRequestProperty(
                "Content-Type",
                png == null ? "application/json" : "multipart/form-data; boundary=" + boundary);
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");

            try (OutputStream stream = connection.getOutputStream()) {
                if (png != null) {
                    stream.write(
                        ("--" + boundary
                            + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                stream.write(
                    json.toString()
                        .getBytes(StandardCharsets.UTF_8));
                if (png != null) {
                    stream.write(
                        ("\r\n--" + boundary
                            + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"crafting.png\"\r\nContent-Type: image/png\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    stream.write(png);
                    stream.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
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

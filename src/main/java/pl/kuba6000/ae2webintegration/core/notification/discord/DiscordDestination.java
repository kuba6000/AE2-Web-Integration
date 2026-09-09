package pl.kuba6000.ae2webintegration.core.notification.discord;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.notification.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.INotificationPayload;

public class DiscordDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger(MODID + " - DISCORD INTEGRATION");

    @Override
    public boolean isUsable() {
        return !Config.DISCORD_WEBHOOK.isEmpty();
    }

    @Override
    public boolean supports(INotificationPayload notificationPayload) {
        return notificationPayload instanceof DiscordPayload;
    }

    @Override
    public void sendNotification(INotificationPayload message) {
        DiscordPayload payload = (DiscordPayload) message;

        JsonObject json = payload.serializePayload();

        URL url = null;
        try {
            url = new URL(Config.DISCORD_WEBHOOK);

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");

            OutputStream stream = connection.getOutputStream();
            stream.write(
                json.toString()
                    .getBytes());
            stream.flush();
            stream.close();

            int code;
            if ((code = connection.getResponseCode()) != 200 && code != 204) {
                LOG.error("Error, response code: {}", code);
            }
        } catch (IOException e) {
            // throw new RuntimeException(e);
        }
    }
}

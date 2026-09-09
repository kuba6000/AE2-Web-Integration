package pl.kuba6000.ae2webintegration.core.notification.ntfy;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.notification.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.INotificationPayload;
import scala.Console;

public class NtfyDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger(MODID + " - NTFY INTEGRATION");

    @Override
    public boolean supports(INotificationPayload notificationPayload) {
        return notificationPayload instanceof NtfyPayload;
    }

    @Override
    public void sendNotification(INotificationPayload message) {
        NtfyPayload payload = (NtfyPayload) message;
        if (Config.NTFY_HOST.isEmpty()) return;

        JsonObject json = payload.serializePayload();

        URL url = null;
        try {
            url = new URL(Config.NTFY_HOST);
            String username = Config.NTFY_USER;
            String password = Config.NTFY_PASSWORD;

            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.UTF_8));
            String authHeaderValue = "Basic " + new String(encodedAuth);
            connection.setRequestProperty("Authorization", authHeaderValue);
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");

            Console.println(json.toString());
            Console.println(url);
            Console.println(authHeaderValue);

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

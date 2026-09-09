package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import scala.Console;

public class NtfyDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger(MODID + " - NTFY INTEGRATION");

    @Override
    public boolean isUsable() {
        return !Config.NTFY_HOST.isEmpty();
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        if (Config.NTFY_HOST.isEmpty()) return;

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
                    + craftingMessage.duration()
                    + "s",
                3,
                tags);
        } else if (message instanceof ErrorMessage errorMessage) {
            ErrorMessage.Severity severity = errorMessage.severity();
            List<String> tags = new ArrayList<>();
            int priority = 3;
            switch (severity) {
                case ERROR -> {
                    priority = 5;
                    tags.add("rotating_light");
                }
                case WARNING -> {
                    priority = 4;
                    tags.add("warning");
                }
            };
            payload = new NtfyPayload(errorMessage.title(), errorMessage.description(), priority, tags);
        }

        if (payload == null) return;
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

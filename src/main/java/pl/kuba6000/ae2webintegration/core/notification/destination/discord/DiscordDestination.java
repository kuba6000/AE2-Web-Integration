package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class DiscordDestination implements INotificationDestination {

    private static final Logger LOG = LogManager.getLogger(MODID + " - DISCORD INTEGRATION");

    @Override
    public boolean isUsable() {
        return !Config.DISCORD_WEBHOOK.isEmpty();
    }

    @Override
    public boolean supports(IMessage message) {
        return true;
    }

    @Override
    public void sendNotification(IMessage message) {
        DiscordPayload payload = null;
        if (message instanceof CraftingMessage craftingMessage) {
            payload = new DiscordPayload(
                "AE2 Job Tracker [ Grid " + craftingMessage.grid() + " ][ " + craftingMessage.cpuName() + " ]",
                "Crafting for `" + craftingMessage.outputItemName()
                    + " x"
                    + craftingMessage.outputItemAmount()
                    + "` "
                    + (craftingMessage.wasCancelled() ? "cancelled" : "completed")
                    + "!\nIt took "
                    + craftingMessage.duration()
                    + "s",
                craftingMessage.wasCancelled() ? 15548997 : 5763719);
        } else if (message instanceof ErrorMessage errorMessage) {
            ErrorMessage.Severity severity = errorMessage.severity();
            int color = switch (severity) {
                case ERROR -> 15548997;
                case WARNING -> 15592002;
                case NONE -> 0;
            };
            if (color != 0) {
                payload = new DiscordPayload(errorMessage.title(), errorMessage.description(), color);
            } else {
                payload = new DiscordPayload(errorMessage.title(), errorMessage.description());
            }
        }

        if (payload == null) return;
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

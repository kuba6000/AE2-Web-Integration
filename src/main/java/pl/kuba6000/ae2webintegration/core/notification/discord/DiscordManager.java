package pl.kuba6000.ae2webintegration.core.notification.discord;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;

public class DiscordManager extends Thread {

    private static final Logger LOG = LogManager.getLogger(MODID + " - DISCORD INTEGRATION");

    private static DiscordManager thread;

    private static ConcurrentLinkedQueue<DiscordPayload> toPush = new ConcurrentLinkedQueue<>();

    public static void init() {
        if (thread != null) return;
        thread = new DiscordManager();
        thread.start();
    }

    public static void postMessageNonBlocking(DiscordPayload message) {
        toPush.offer(message);
    }

    private static void postMessage(DiscordPayload message) {
        if (Config.DISCORD_WEBHOOK.isEmpty()) return;

        JsonObject json = message.serializePayload();

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

    @Override
    public void run() {
        while (true) {
            if (toPush.peek() != null) {
                DiscordPayload message;
                while ((message = toPush.poll()) != null) {
                    postMessage(message);
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // throw new RuntimeException(e);
            }
        }
    }
}

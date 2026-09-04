package pl.kuba6000.ae2webintegration.core.ntfy;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import scala.Console;

public class NtfyManager extends Thread {

    private static final Logger LOG = LogManager.getLogger(MODID + " - NTFY INTEGRATION");

    private static NtfyManager thread;

    private static ConcurrentLinkedQueue<NtfyJsonMessage> toPush = new ConcurrentLinkedQueue<>();

    public static void init() {
        if (thread != null) return;
        thread = new NtfyManager();
        thread.start();
    }

    public static void postMessageNonBlocking(NtfyJsonMessage message) {
        toPush.offer(message);
    }

    public static class NtfyJsonMessage {

        String title;
        String description;
        int priority;

        public NtfyJsonMessage(String title, String description, int priority) {
            this.title = title;
            this.description = description;
            this.priority = priority;
        }

        public NtfyJsonMessage(String title, String description) {
            this(title, description, 3);
        }
    }

    private static void postMessage(NtfyJsonMessage message) {
        if (Config.NTFY_HOST.isEmpty()) return;

        String topic = Config.NTFY_TOPIC;

        JsonObject json = new JsonObject();
        json.addProperty("topic", topic);
        json.addProperty("message", message.description);
        json.addProperty("title", message.title);
        // json.addProperty("tags", "");
        json.addProperty("priority", message.priority);
        if (!Config.AE_FULL_DOMAIN.isEmpty()) {
            json.addProperty("click", Config.AE_FULL_DOMAIN);
        }

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

    @Override
    public void run() {
        while (true) {
            if (toPush.peek() != null) {
                NtfyJsonMessage message;
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

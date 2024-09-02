package com.kuba6000.ae2webintegration.discord;

import static com.kuba6000.ae2webintegration.AE2WebIntegration.MODID;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kuba6000.ae2webintegration.Config;

public class DiscordManager extends Thread {

    private static final Logger LOG = LogManager.getLogger(MODID + " - DISCORD INTEGRATION");

    private static DiscordManager thread;

    private static ConcurrentLinkedQueue<DiscordEmbed> toPush = new ConcurrentLinkedQueue<>();

    public static void init() {
        if (thread != null) return;
        thread = new DiscordManager();
        thread.start();
    }

    public static void postMessageNonBlocking(DiscordEmbed message) {
        toPush.offer(message);
    }

    public static class DiscordEmbed {

        String title;
        String description;
        int color;

        public DiscordEmbed(String title, String description, int color) {
            this.title = title;
            this.description = description;
            this.color = color;
        }

        public DiscordEmbed(String title, String description) {
            this(title, description, 1752220);
        }
    }

    private static void postMessage(DiscordEmbed message) {
        if (Config.DISCORD_WEBHOOK.isEmpty()) return;

        String roleID = Config.DISCORD_ROLE_ID;

        JsonObject json = new JsonObject();
        json.addProperty("username", "AE2 Web Integration");
        json.addProperty("content", !roleID.isEmpty() ? "<@&" + roleID + ">" : "");
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", message.title);
        embed.addProperty("description", message.description);
        embed.addProperty("color", message.color);
        embeds.add(embed);
        json.add("embeds", embeds);
        json.add("attachments", new JsonArray());

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
            if ((code = connection.getResponseCode()) != 200) {
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
                DiscordEmbed message;
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

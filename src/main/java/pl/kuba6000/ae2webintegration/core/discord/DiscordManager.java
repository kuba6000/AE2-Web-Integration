package pl.kuba6000.ae2webintegration.core.discord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;

public class DiscordManager extends Thread {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - DISCORD INTEGRATION");

    private static DiscordManager thread;

    private static ConcurrentLinkedQueue<DiscordEmbed> toPush = new ConcurrentLinkedQueue<>();

    public static void init() {
        if (thread != null) return;
        thread = new DiscordManager();
        thread.setDaemon(true);
        thread.start();
    }

    public static void postMessageNonBlocking(DiscordEmbed message) {
        toPush.offer(message);
    }

    public static String formatDuration(long durationMillis) {
        if (durationMillis < 5000L) {
            return durationMillis / 1000d + "s";
        }

        long totalSeconds = Math.round(durationMillis / 1000d);
        long days = totalSeconds / 86400L;
        long hours = totalSeconds % 86400L / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0L) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0L) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static boolean shouldPostCraftingNotification(long durationMillis, long craftedAmount) {
        long minimumDurationMillis = Config.DISCORD_MINIMUM_CRAFTING_DURATION_SECONDS() * 1000L;
        return durationMillis >= minimumDurationMillis && craftedAmount >= Config.DISCORD_MINIMUM_CRAFTING_AMOUNT();
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
        if (Config.DISCORD_WEBHOOK()
            .isEmpty()) return;

        String roleID = Config.DISCORD_ROLE_ID();

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
            url = new URL(Config.DISCORD_WEBHOOK());

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(
                    json.toString()
                        .getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }

            int code;
            if ((code = connection.getResponseCode()) != 200 && code != 204) {
                LOG.error("Error, response code: " + code);
            }
        } catch (IOException e) {
            // throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            if (toPush.peek() != null) {
                DiscordEmbed message;
                while ((message = toPush.poll()) != null) {
                    postMessage(message);
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                interrupt();
            }
        }
    }
}

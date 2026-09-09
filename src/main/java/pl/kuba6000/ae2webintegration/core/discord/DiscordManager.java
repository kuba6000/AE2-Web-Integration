package pl.kuba6000.ae2webintegration.core.discord;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;

public class DiscordManager extends Thread {

    public static final int COLOR_TURQUOISE = 0x1ABC9C;
    public static final int COLOR_RED = 0xED4245;
    public static final int COLOR_GREEN = 0x57F287;

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - DISCORD INTEGRATION");

    private static DiscordManager thread;

    private static final BlockingQueue<DiscordEmbed> toPush = new LinkedBlockingQueue<>();
    private static final int WEBHOOK_TIMEOUT_MILLIS = 10_000;
    private static final long FRACTIONAL_SECONDS_THRESHOLD_MILLIS = 5000L;

    public static void init() {
        if (thread != null) return;
        thread = new DiscordManager();
        thread.setDaemon(true);
        thread.start();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Enqueue success is not exposed by this fire-and-forget API.
    public static void postMessageNonBlocking(DiscordEmbed message) {
        toPush.offer(message);
    }

    @SuppressWarnings("PMD.AvoidMagicNumbers") // Standard conversions between milliseconds, seconds, minutes, hours and
                                               // days.
    public static String formatDuration(long durationMillis) {
        if (durationMillis < FRACTIONAL_SECONDS_THRESHOLD_MILLIS) {
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
        // Seconds to milliseconds.
        long minimumDurationMillis = Config.DISCORD_MINIMUM_CRAFTING_DURATION_SECONDS() * 1000L; // NOPMD
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
            this(title, description, COLOR_TURQUOISE);
        }
    }

    private static void postMessage(DiscordEmbed message) {
        String webhook = Config.DISCORD_WEBHOOK();
        if (webhook.isEmpty()) return;

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

        HttpsURLConnection connection = null;
        try {
            URL url = new URL(webhook);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                LOG.error("Discord webhook URL must use HTTPS");
                return;
            }

            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(WEBHOOK_TIMEOUT_MILLIS);
            connection.setReadTimeout(WEBHOOK_TIMEOUT_MILLIS);
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
            if ((code = connection.getResponseCode()) != 200 && code != 204) { // NOPMD - HTTP OK and No Content.
                LOG.error("Error, response code: {}", code);
            }
        } catch (IOException | IllegalArgumentException e) {
            // Exception messages may contain the webhook URL, including its secret token.
            LOG.error(
                "Discord webhook request failed ({})",
                e.getClass()
                    .getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                postMessage(toPush.take());
            } catch (InterruptedException e) {
                interrupt();
            }
        }
    }
}

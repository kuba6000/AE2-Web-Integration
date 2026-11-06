package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import static pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination.COLOR_TURQUOISE;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationPayload;

public class DiscordPayload implements INotificationPayload {

    final String title;
    final String description;
    final int color;
    private boolean includeImage;
    private boolean thumbnail;
    private final String roleId;

    public DiscordPayload(String title, String description, int color) {
        this(title, description, color, Config.INSTANCE.discord.roleId);
    }

    DiscordPayload(String title, String description, int color, String roleId) {
        this.roleId = roleId;
        this.title = title;
        this.description = description;
        this.color = color;
    }

    public DiscordPayload(String title, String description) {
        this(title, description, COLOR_TURQUOISE);
    }

    void includeImage(boolean thumbnail) {
        includeImage = true;
        this.thumbnail = thumbnail;
    }

    @Override
    public JsonObject serializePayload() {
        JsonObject json = new JsonObject();
        json.addProperty("username", "AE2 Web Integration");
        json.addProperty("content", !roleId.isEmpty() ? "<@&" + roleId + ">" : "");
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", limit(this.title, 256));
        embed.addProperty("description", limit(this.description, 4096));
        embed.addProperty("color", this.color);
        if (includeImage) {
            JsonObject image = new JsonObject();
            image.addProperty("url", "attachment://crafting.png");
            embed.add(thumbnail ? "thumbnail" : "image", image);
        }
        embeds.add(embed);
        json.add("embeds", embeds);
        JsonArray attachments = new JsonArray();
        if (includeImage) {
            JsonObject attachment = new JsonObject();
            attachment.addProperty("id", 0);
            attachment.addProperty("filename", "crafting.png");
            attachments.add(attachment);
        }
        json.add("attachments", attachments);

        return json;
    }

    private static String limit(String value, int maximum) {
        if (value.length() <= maximum) return value;
        int end = maximum - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
}

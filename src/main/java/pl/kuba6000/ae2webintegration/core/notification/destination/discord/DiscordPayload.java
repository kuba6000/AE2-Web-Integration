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

    @Override
    public JsonObject serializePayload() {
        JsonObject json = new JsonObject();
        json.addProperty("username", "AE2 Web Integration");
        json.addProperty("content", !roleId.isEmpty() ? "<@&" + roleId + ">" : "");
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", this.title);
        embed.addProperty("description", this.description);
        embed.addProperty("color", this.color);
        embeds.add(embed);
        json.add("embeds", embeds);
        json.add("attachments", new JsonArray());

        return json;
    }
}

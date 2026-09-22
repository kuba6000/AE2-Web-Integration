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

    public DiscordPayload(String title, String description, int color) {
        this.title = title;
        this.description = description;
        this.color = color;
    }

    public DiscordPayload(String title, String description) {
        this(title, description, COLOR_TURQUOISE);
    }

    @Override
    public JsonObject serializePayload() {
        String roleID = Config.INSTANCE.discord.roleId;

        JsonObject json = new JsonObject();
        json.addProperty("username", "AE2 Web Integration");
        json.addProperty("content", !roleID.isEmpty() ? "<@&" + roleID + ">" : "");
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

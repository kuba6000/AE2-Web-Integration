package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationPayload;

public class NtfyPayload implements INotificationPayload {

    final String title;
    final String description;
    final int priority;
    final List<String> tags;

    public NtfyPayload(String title, String description, int priority, List<String> tags) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.tags = tags;
    }

    public NtfyPayload(String title, String description, int priority) {
        this(title, description, priority, new ArrayList<>());
    }

    public NtfyPayload(String title, String description) {
        this(title, description, 3, new ArrayList<>());
    }

    @Override
    public JsonObject serializePayload() {
        String topic = Config.NTFY_TOPIC();

        JsonObject json = new JsonObject();
        json.addProperty("topic", topic);
        json.addProperty("message", this.description);
        json.addProperty("title", this.title);

        JsonArray tagsArray = new Gson().toJsonTree(tags)
            .getAsJsonArray();
        json.add("tags", tagsArray);

        json.addProperty("priority", this.priority);
        if (!Config.AE_FULL_DOMAIN()
            .isEmpty()) {
            json.addProperty("click", Config.AE_FULL_DOMAIN());
        }

        return json;
    }
}

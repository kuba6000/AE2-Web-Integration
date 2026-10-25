package pl.kuba6000.ae2webintegration.core.notification.destination.ntfy;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationPayload;

public class NtfyPayload implements INotificationPayload {

    public enum Priority {

        MAX(5),
        HIGH(4),
        NORMAL(3),
        LOW(2),
        MIN(1);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    final String title;
    final String description;
    final Priority priority;
    final List<String> tags;
    private final String topic;
    private final String fullDomain;

    public NtfyPayload(String title, String description, Priority priority, List<String> tags) {
        this(title, description, priority, tags, Config.INSTANCE);
    }

    NtfyPayload(String title, String description, Priority priority, List<String> tags, ConfigSettings settings) {
        this.topic = settings.ntfy.topic;
        this.fullDomain = settings.notifications.fullDomain;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.tags = tags;
    }

    public NtfyPayload(String title, String description, Priority priority) {
        this(title, description, priority, new ArrayList<>());
    }

    public NtfyPayload(String title, String description) {
        this(title, description, Priority.NORMAL, new ArrayList<>());
    }

    @Override
    public JsonObject serializePayload() {
        JsonObject json = new JsonObject();
        json.addProperty("topic", topic);
        json.addProperty("message", this.description);
        json.addProperty("title", this.title);

        JsonArray tagsArray = new Gson().toJsonTree(tags)
            .getAsJsonArray();
        json.add("tags", tagsArray);

        json.addProperty("priority", this.priority.getValue());
        if (!fullDomain.isEmpty()) {
            json.addProperty("click", fullDomain);
        }

        return json;
    }
}

package pl.kuba6000.ae2webintegration.core.notification.ntfy;

import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.Config;
import pl.kuba6000.ae2webintegration.core.notification.INotificationPayload;

public class NtfyPayload implements INotificationPayload {

    String title;
    String description;
    int priority;

    public NtfyPayload(String title, String description, int priority) {
        this.title = title;
        this.description = description;
        this.priority = priority;
    }

    public NtfyPayload(String title, String description) {
        this(title, description, 3);
    }

    @Override
    public JsonObject serializePayload() {
        String topic = Config.NTFY_TOPIC;

        JsonObject json = new JsonObject();
        json.addProperty("topic", topic);
        json.addProperty("message", this.description);
        json.addProperty("title", this.title);
        // json.addProperty("tags", "");
        json.addProperty("priority", this.priority);
        if (!Config.AE_FULL_DOMAIN.isEmpty()) {
            json.addProperty("click", Config.AE_FULL_DOMAIN);
        }

        return json;
    }
}

package pl.kuba6000.ae2webintegration.core.notification.destination;

import com.google.gson.JsonObject;

public interface INotificationPayload {

    JsonObject serializePayload();
}

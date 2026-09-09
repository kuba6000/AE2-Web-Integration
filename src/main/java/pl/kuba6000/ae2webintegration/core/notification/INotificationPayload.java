package pl.kuba6000.ae2webintegration.core.notification;

import com.google.gson.JsonObject;

public interface INotificationPayload {

    JsonObject serializePayload();
}

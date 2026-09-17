package pl.kuba6000.ae2webintegration.core.notification.destination;

import com.google.gson.JsonObject;

/**
 * Implement INotificationPayload for a payload supported by the notification destination.
 * Examples can be found for Discord and Ntfy
 */
public interface INotificationPayload {

    /**
     * The payload should be serializable to json for WebhookHelper
     * 
     * @return the payload serialized in json format
     */
    JsonObject serializePayload();
}

package pl.kuba6000.ae2webintegration.core.notification.destination;

import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

/**
 * Implement INotificationDestination for your notification service.
 * Examples can be found for Discord and Ntfy
 */
public interface INotificationDestination {

    /**
     * isUsable is used to check the configuration values,
     * e.g. whether the notification destination is enabled, are all required parameters set and valid
     * 
     * @return boolean, false disables this destination in NotificationManager
     */
    boolean isUsable();

    /**
     * you can implement filtering by message type here
     * 
     * @param message the message type to check
     * @return true, if this message type is supported, false, if it can / should not be sent
     */
    boolean supports(IMessage message);

    /**
     * Implement the notification sending logic here.
     * You can use WebhookHelper if you want to send a payload (serialized in json format) via http(s).
     * Here, you also have to create a payload (that your notification destination supports) from the message.
     * 
     * @param message the message to be sent to the notification destination
     */
    void sendNotification(IMessage message);
}

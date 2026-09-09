package pl.kuba6000.ae2webintegration.core.notification;

public interface INotificationDestination {
    boolean supports(INotificationPayload notificationPayload);
    void sendNotification(INotificationPayload payload);
}

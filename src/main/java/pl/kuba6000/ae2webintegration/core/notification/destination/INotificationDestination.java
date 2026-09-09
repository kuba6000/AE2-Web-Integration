package pl.kuba6000.ae2webintegration.core.notification.destination;

import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public interface INotificationDestination {

    boolean isUsable();

    boolean supports(IMessage message);

    void sendNotification(IMessage message);
}

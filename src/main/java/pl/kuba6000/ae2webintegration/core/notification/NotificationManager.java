package pl.kuba6000.ae2webintegration.core.notification;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class NotificationManager extends Thread {

    private static final Logger LOG = LogManager.getLogger(MODID + " - WEBHOOK NOTIFICATION INTEGRATION");

    private static NotificationManager thread;

    private static final ConcurrentLinkedQueue<IMessage> toPush = new ConcurrentLinkedQueue<>();
    private static final List<INotificationDestination> destinations = new ArrayList<>();

    public static void init() {
        if (thread != null) return;

        DiscordDestination discord = new DiscordDestination();
        if (discord.isUsable()) destinations.add(discord);
        NtfyDestination ntfy = new NtfyDestination();
        if (ntfy.isUsable()) destinations.add(ntfy);

        thread = new NotificationManager();
        thread.start();
    }

    public static void postMessageNonBlocking(IMessage message) {
        toPush.offer(message);
    }

    private static void postMessage(IMessage message) {
        for (INotificationDestination destination : destinations) {
            if (destination.supports(message)) {
                destination.sendNotification(message);
            }
        }
    }

    @Override
    public void run() {
        while (true) {
            if (toPush.peek() != null) {
                IMessage message;
                while ((message = toPush.poll()) != null) {
                    postMessage(message);
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // throw new RuntimeException(e);
            }
        }
    }
}

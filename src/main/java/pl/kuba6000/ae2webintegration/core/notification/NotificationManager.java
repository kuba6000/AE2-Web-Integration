package pl.kuba6000.ae2webintegration.core.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;

public class NotificationManager extends Thread {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - WEBHOOK NOTIFICATION INTEGRATION");

    private static NotificationManager thread;

    private static final ConcurrentLinkedQueue<IMessage> toPush = new ConcurrentLinkedQueue<>();
    private static final List<INotificationDestination> destinations = new ArrayList<>();

    public static void init() {
        LOG.info("Initializing NotificationManager");
        if (thread != null) return;

        DiscordDestination discord = new DiscordDestination();
        if (discord.isUsable()) destinations.add(discord);
        NtfyDestination ntfy = new NtfyDestination();
        if (ntfy.isUsable()) destinations.add(ntfy);

        if (!Config.AE_PUBLIC_MODE() && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(
                new ErrorMessage(
                    "AE2 Web Integration",
                    "Notification integration started!",
                    ErrorMessage.Severity.NONE));
        } else if (Config.AE_PUBLIC_MODE() && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(
                new ErrorMessage(
                    "AE2 Web Integration",
                    "Warning!\nNotifications are enabled in the config, but the public mode is enabled!\nNotifications will be disabled!",
                    ErrorMessage.Severity.WARNING));
        }

        thread = new NotificationManager();
        thread.setDaemon(true);
        thread.start();
    }

    public static void postMessageNonBlocking(IMessage message) {
        toPush.offer(message);
    }

    public static String formatDuration(long durationMillis) {
        if (durationMillis < 5000L) {
            return durationMillis / 1000d + "s";
        }

        long totalSeconds = Math.round(durationMillis / 1000d);
        long days = totalSeconds / 86400L;
        long hours = totalSeconds % 86400L / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;

        if (days > 0L) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0L) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static boolean shouldPostCraftingNotification(long durationMillis, long craftedAmount) {
        long minimumDurationMillis = pl.kuba6000.ae2webintegration.core.config.Config
            .NOTIFICATION_MINIMUM_CRAFTING_DURATION_SECONDS() * 1000L;
        return durationMillis >= minimumDurationMillis
            && craftedAmount >= pl.kuba6000.ae2webintegration.core.config.Config.NOTIFICATION_MINIMUM_CRAFTING_AMOUNT();
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
        while (!isInterrupted()) {
            if (toPush.peek() != null) {
                IMessage message;
                while ((message = toPush.poll()) != null) {
                    postMessage(message);
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                interrupt();
            }
        }
    }
}

package pl.kuba6000.ae2webintegration.core.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    private static final BlockingQueue<IMessage> toPush = new LinkedBlockingQueue<>();
    private static final List<INotificationDestination> destinations = new ArrayList<>();

    private static final long FRACTIONAL_SECONDS_THRESHOLD_MILLIS = 5000L;

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
            NotificationManager.postMessageNonBlocking(new ErrorMessage("AE2 Web Integration", """
                Warning!
                Notifications are enabled in the config, but the public mode is enabled!
                Notifications will be disabled!""", ErrorMessage.Severity.WARNING));
        }

        thread = new NotificationManager();
        thread.setDaemon(true);
        thread.start();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Enqueue success is not exposed by this fire-and-forget API.
    public static void postMessageNonBlocking(IMessage message) {
        toPush.offer(message);
    }

    public static String formatDuration(long durationMillis) {
        if (durationMillis < FRACTIONAL_SECONDS_THRESHOLD_MILLIS) {
            return durationMillis / (double) TimeUnit.SECONDS.toMillis(1) + "s";
        }

        long totalSeconds = Math.round(durationMillis / (double) TimeUnit.SECONDS.toMillis(1));
        long days = TimeUnit.SECONDS.toDays(totalSeconds);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds % TimeUnit.DAYS.toSeconds(1));
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds % TimeUnit.HOURS.toSeconds(1));
        long seconds = totalSeconds % TimeUnit.MINUTES.toSeconds(1);

        if (days > 0L) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0L) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static boolean shouldPostCraftingNotification(long durationMillis, long craftedAmount) {
        long minimumDurationMillis = TimeUnit.SECONDS.toMillis(Config.NOTIFICATION_MINIMUM_CRAFTING_DURATION_SECONDS());
        return durationMillis >= minimumDurationMillis
            && craftedAmount >= Config.NOTIFICATION_MINIMUM_CRAFTING_AMOUNT();
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
            try {
                postMessage(toPush.take());
            } catch (InterruptedException e) {
                interrupt();
            }
        }
    }
}

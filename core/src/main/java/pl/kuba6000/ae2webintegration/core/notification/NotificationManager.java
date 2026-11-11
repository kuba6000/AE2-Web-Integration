package pl.kuba6000.ae2webintegration.core.notification;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage.Severity;

public class NotificationManager extends Thread {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - WEBHOOK NOTIFICATION INTEGRATION");

    private static NotificationManager thread;

    private static final BlockingQueue<IMessage> toPush = new LinkedBlockingQueue<>();
    private static volatile List<INotificationDestination> destinations = Collections.emptyList();

    private static final long FRACTIONAL_SECONDS_THRESHOLD_MILLIS = 5000L;

    public static void init() {
        LOG.info("Initializing NotificationManager");
        if (thread != null) shutdown();

        reloadSettings();

        if (!Config.INSTANCE.general.publicMode && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(
                new StatusMessage("AE2 Web Integration", "Notification integration started!", Severity.NONE));
        } else if (Config.INSTANCE.general.publicMode && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(new StatusMessage("AE2 Web Integration", """
                Warning!
                Notifications are enabled in the config, but the public mode is enabled!
                Notifications will be disabled!""", Severity.WARNING));
        }

        thread = new NotificationManager();
        thread.setDaemon(true);
        thread.start();
    }

    /** Replaces destinations without starting a worker or sending a startup notification. */
    public static void reloadSettings() {
        List<INotificationDestination> updated = new ArrayList<>();
        DiscordDestination discord = new DiscordDestination();
        if (discord.isUsable()) updated.add(discord);
        NtfyDestination ntfy = new NtfyDestination();
        if (ntfy.isUsable()) updated.add(ntfy);
        destinations = Collections.unmodifiableList(updated);
    }

    public static void shutdown() {
        thread.interrupt();
        thread = null;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Enqueue success is not exposed by this fire-and-forget API.
    public static void postMessageNonBlocking(IMessage message) {
        toPush.offer(message);
    }

    public static String formatDuration(long durationMillis) {
        if (durationMillis < FRACTIONAL_SECONDS_THRESHOLD_MILLIS) {
            return durationMillis / (double) SECONDS.toMillis(1) + "s";
        }

        long totalSeconds = Math.round(durationMillis / (double) SECONDS.toMillis(1));
        long days = SECONDS.toDays(totalSeconds);
        long hours = SECONDS.toHours(totalSeconds % DAYS.toSeconds(1));
        long minutes = SECONDS.toMinutes(totalSeconds % HOURS.toSeconds(1));
        long seconds = totalSeconds % MINUTES.toSeconds(1);

        if (days > 0L) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0L) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0L) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    public static boolean shouldPostCraftingNotification(long durationMillis, long craftedAmount) {
        long minimumDurationMillis = SECONDS.toMillis(Config.INSTANCE.notifications.minimumCraftingDurationSeconds);
        return durationMillis >= minimumDurationMillis
            && craftedAmount >= Config.INSTANCE.notifications.minimumCraftingAmount;
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

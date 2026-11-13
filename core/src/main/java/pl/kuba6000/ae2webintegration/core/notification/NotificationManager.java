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
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.notification.destination.INotificationDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.IMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage.Severity;

public class NotificationManager extends Thread {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration" + " - WEBHOOK NOTIFICATION INTEGRATION");

    private static @Nullable NotificationManager thread;

    private static final int MAX_PENDING_MESSAGES = 256;
    private final BlockingQueue<IMessage> toPush = new LinkedBlockingQueue<>(MAX_PENDING_MESSAGES);
    private volatile List<INotificationDestination> destinations;
    private volatile boolean running = true;

    private static final long FRACTIONAL_SECONDS_THRESHOLD_MILLIS = 5000L;

    private NotificationManager(List<INotificationDestination> destinations) {
        super("AE2Web-Notifications");
        this.destinations = destinations;
        setDaemon(true);
    }

    public static synchronized void init() {
        LOG.info("Initializing NotificationManager");
        shutdown();

        List<INotificationDestination> destinations = configuredDestinations();
        NotificationManager worker = new NotificationManager(destinations);
        thread = worker;

        if (!Config.INSTANCE.general.publicMode && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(
                new StatusMessage("AE2 Web Integration", "Notification integration started!", Severity.NONE));
        } else if (Config.INSTANCE.general.publicMode && (!destinations.isEmpty())) {
            NotificationManager.postMessageNonBlocking(new StatusMessage("AE2 Web Integration", """
                Warning!
                Notifications are enabled in the config, but the public mode is enabled!
                Notifications will be disabled!""", Severity.WARNING));
        }

        worker.start();
    }

    /** Replaces destinations without starting a worker or sending a startup notification. */
    public static synchronized void reloadSettings() {
        if (thread != null) thread.destinations = configuredDestinations();
    }

    private static List<INotificationDestination> configuredDestinations() {
        List<INotificationDestination> updated = new ArrayList<>();
        DiscordDestination discord = new DiscordDestination();
        if (discord.isUsable()) updated.add(discord);
        NtfyDestination ntfy = new NtfyDestination();
        if (ntfy.isUsable()) updated.add(ntfy);
        return Collections.unmodifiableList(updated);
    }

    public static synchronized void shutdown() {
        NotificationManager previous = thread;
        thread = null;
        if (previous != null) {
            previous.running = false;
            previous.interrupt();
            previous.toPush.clear();
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Fire-and-forget delivery drops newest when full or stopped.
    public static synchronized void postMessageNonBlocking(IMessage message) {
        if (thread != null) thread.toPush.offer(message);
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

    private void postMessage(IMessage message) {
        for (INotificationDestination destination : destinations) {
            if (!running) return;
            try {
                if (destination.supports(message)) {
                    destination.sendNotification(message);
                }
            } catch (RuntimeException failure) {
                // Destination exceptions can contain webhook credentials or player/resource names.
                LOG.warn(
                    "Notification delivery failed ({})",
                    failure.getClass()
                        .getSimpleName());
            }
        }
    }

    @Override
    public void run() {
        while (running && !isInterrupted()) {
            try {
                postMessage(toPush.take());
            } catch (InterruptedException e) {
                interrupt();
            }
        }
    }
}

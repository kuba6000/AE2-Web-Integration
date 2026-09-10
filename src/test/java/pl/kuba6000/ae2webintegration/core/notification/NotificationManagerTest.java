package pl.kuba6000.ae2webintegration.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.ErrorMessage;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class NotificationManagerTest {

    private IConfigValue<Integer> previousMinimumDuration;
    private IConfigValue<Integer> previousMinimumAmount;
    private IConfigValue<String> previousWebhook;
    private IConfigValue<String> previousRole;

    @BeforeEach
    void resetConfig() {
        previousMinimumDuration = ConfigBootstrap.notificationMinimumCraftingDurationSecondsValue;
        previousMinimumAmount = ConfigBootstrap.notificationMinimumCraftingAmountValue;
        previousWebhook = ConfigBootstrap.discordWebhookValue;
        previousRole = ConfigBootstrap.discordRoleIdValue;
        ConfigBootstrap.notificationMinimumCraftingDurationSecondsValue = () -> 0;
        ConfigBootstrap.notificationMinimumCraftingAmountValue = () -> 0;
    }

    @AfterEach
    void restoreConfig() {
        ConfigBootstrap.notificationMinimumCraftingAmountValue = previousMinimumAmount;
        ConfigBootstrap.notificationMinimumCraftingDurationSecondsValue = previousMinimumDuration;
        ConfigBootstrap.discordWebhookValue = previousWebhook;
        ConfigBootstrap.discordRoleIdValue = previousRole;
    }

    @Test
    void invalidWebhooksAreDiagnosedWithoutKillingTheNotificationWorker() throws InterruptedException {
        AtomicReference<String> webhook = new AtomicReference<>("malformed-webhook");
        ConfigBootstrap.discordWebhookValue = webhook::get;
        ConfigBootstrap.discordRoleIdValue = () -> "";
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        Logger logger = (Logger) LogManager.getLogger("ae2webintegration - DISCORD INTEGRATION");
        AbstractAppender appender = new AbstractAppender("discord-errors", null, null, false, Property.EMPTY_ARRAY) {

            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.ERROR) {
                    assertTrue(
                        errors.offer(
                            event.getMessage()
                                .getFormattedMessage()));
                }
            }
        };
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        NotificationManager worker = new NotificationManager();
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler((thread, error) -> workerFailure.set(error));
        appender.start();
        logger.addAppender(appender);
        worker.start();
        try {
            DiscordDestination discordDestination = new DiscordDestination();
            discordDestination.sendNotification(new ErrorMessage("First", "First message", ErrorMessage.Severity.NONE));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Malformed webhook must be diagnosed");

            // No connection should be opened for a protocol Discord webhooks do not support.
            webhook.set("http://127.0.0.1:1/webhook");
            discordDestination
                .sendNotification(new ErrorMessage("Second", "Second message", ErrorMessage.Severity.NONE));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Unsupported protocol must be diagnosed");

            webhook.set("https://localhost:65536/webhook");
            discordDestination.sendNotification(new ErrorMessage("Third", "Third message", ErrorMessage.Severity.NONE));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Invalid port must be diagnosed");

            webhook.set("another-malformed-webhook");
            discordDestination
                .sendNotification(new ErrorMessage("Fourth", "Fourth message", ErrorMessage.Severity.NONE));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Worker must continue processing the queue");
            assertNull(workerFailure.get());
        } finally {
            worker.interrupt();
            worker.join(3000);
            logger.removeAppender(appender);
            appender.stop();
        }
        assertFalse(worker.isAlive(), "Idle worker must stop after interruption");
    }

    @ParameterizedTest
    @CsvSource({ "250, 0.25s", "3285, 3.285s", "4999, 4.999s", "5000, 5s", "59499, 59s", "59500, '1m 0s'",
        "3599500, '1h 0m 0s'", "86399500, '1d 0h 0m 0s'", "47000, 47s", "800000, '13m 20s'", "3661000, '1h 1m 1s'",
        "7509000, '2h 5m 9s'", "86400000, '1d 0h 0m 0s'", "183845000, '2d 3h 4m 5s'" })
    void formatsCraftingDuration(long durationMillis, String expected) {
        assertEquals(expected, NotificationManager.formatDuration(durationMillis));
    }

    @Test
    void durationThresholdFiltersShortCraftingJobs() {
        ConfigBootstrap.notificationMinimumCraftingDurationSecondsValue = () -> 300;

        assertFalse(NotificationManager.shouldPostCraftingNotification(299_999L, 1L));
        assertTrue(NotificationManager.shouldPostCraftingNotification(300_000L, 1L));
    }

    @Test
    void amountThresholdFiltersSmallCraftingJobs() {
        ConfigBootstrap.notificationMinimumCraftingAmountValue = () -> 1000;

        assertFalse(NotificationManager.shouldPostCraftingNotification(1L, 999L));
        assertTrue(NotificationManager.shouldPostCraftingNotification(1L, 1000L));
    }
}
